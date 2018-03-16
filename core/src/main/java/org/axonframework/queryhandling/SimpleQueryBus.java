/*
 * Copyright (c) 2010-2017. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.axonframework.queryhandling;

import org.axonframework.common.Registration;
import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.messaging.DefaultInterceptorChain;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.axonframework.queryhandling.responsetypes.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Implementation of the QueryBus that dispatches queries to the handlers within the JVM. Any timeouts are ignored by
 * this implementation, as handlers are considered to answer immediately.
 * <p>
 * In case multiple handlers are registered for the same query and response type, the {@link #query(QueryMessage)}
 * method will invoke one of these handlers. Which one is unspecified.
 *
 * @author Marc Gathier
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 3.1
 */
public class SimpleQueryBus implements QueryBus {

    private static final Logger logger = LoggerFactory.getLogger(SimpleQueryBus.class);

    private final ConcurrentMap<String, CopyOnWriteArrayList<QuerySubscription>> subscriptions = new ConcurrentHashMap<>();
    private final MessageMonitor<? super QueryMessage<?, ?>> messageMonitor;
    private final QueryInvocationErrorHandler errorHandler;
    private final List<MessageHandlerInterceptor<? super QueryMessage<?, ?>>> handlerInterceptors = new CopyOnWriteArrayList<>();
    private final List<MessageDispatchInterceptor<? super QueryMessage<?, ?>>> dispatchInterceptors = new CopyOnWriteArrayList<>();

    /**
     * Initialize the query bus without monitoring on messages and a {@link LoggingQueryInvocationErrorHandler}.
     */
    public SimpleQueryBus() {
        this(NoOpMessageMonitor.INSTANCE, NoTransactionManager.instance(),
             new LoggingQueryInvocationErrorHandler(logger));
    }

    /**
     * Initialize the query bus using given {@code transactionManager} to manage transactions around query execution
     * with. No monitoring is applied to messages and a {@link LoggingQueryInvocationErrorHandler} is used
     * to log errors on handlers during a scatter-gather query.
     *
     * @param transactionManager The transaction manager to manage transactions around query execution with
     */
    public SimpleQueryBus(TransactionManager transactionManager) {
        this(NoOpMessageMonitor.INSTANCE, transactionManager, new LoggingQueryInvocationErrorHandler(logger));
    }

    /**
     * Initialize the query bus with the given {@code messageMonitor} and given {@code errorHandler}.
     *
     * @param messageMonitor     The message monitor notified for incoming messages and their result
     * @param transactionManager The transaction manager to manage transactions around query execution with
     * @param errorHandler       The error handler to invoke when query handler report an error
     */
    public SimpleQueryBus(MessageMonitor<? super QueryMessage<?, ?>> messageMonitor,
                          TransactionManager transactionManager,
                          QueryInvocationErrorHandler errorHandler) {
        this.messageMonitor = messageMonitor != null ? messageMonitor : NoOpMessageMonitor.instance();
        this.errorHandler = getOrDefault(errorHandler, () -> new LoggingQueryInvocationErrorHandler(logger));
        if (transactionManager != null) {
            registerHandlerInterceptor(new TransactionManagingInterceptor<>(transactionManager));
        }
    }

    @Override
    public <R> Registration subscribe(String queryName,
                                      Type responseType,
                                      MessageHandler<? super QueryMessage<?, R>> handler) {
        CopyOnWriteArrayList<QuerySubscription> handlers =
                subscriptions.computeIfAbsent(queryName, k -> new CopyOnWriteArrayList<>());
        QuerySubscription<R> querySubscription = new QuerySubscription<>(responseType, handler);
        handlers.addIfAbsent(querySubscription);

        return () -> unsubscribe(queryName, querySubscription);
    }

    @Override
    public <I, U> Registration subscribe(String queryName,
                                         Type initialResponseType,
                                         Type updateResponseType,
                                         SubscriptionQueryMessageHandler<? super QueryMessage<?, I>, I, U> handler) {
        CopyOnWriteArrayList<QuerySubscription> handlers =
                subscriptions.computeIfAbsent(queryName, k -> new CopyOnWriteArrayList<>());
        SubscribableQuerySubscription<I, U> querySubscription = new SubscribableQuerySubscription<>(initialResponseType,
                                                                                                    updateResponseType,
                                                                                                    handler);
        handlers.addIfAbsent(querySubscription);

        return () -> unsubscribe(queryName, querySubscription);
    }

    private boolean unsubscribe(String queryName,
                                QuerySubscription querySubscription) {
        subscriptions.computeIfPresent(queryName, (key, handlers) -> {
            handlers.remove(querySubscription);
            if (handlers.isEmpty()) {
                return null;
            }
            return handlers;
        });
        return true;
    }

    @Override
    public <Q, R> CompletableFuture<QueryResponseMessage<R>> query(QueryMessage<Q, R> query) {
        MessageMonitor.MonitorCallback monitorCallback = messageMonitor.onMessageIngested(query);
        QueryMessage<Q, R> interceptedQuery = intercept(query);
        CompletableFuture<QueryResponseMessage<R>> completableFuture = new CompletableFuture<>();
        List<MessageHandler<? super QueryMessage<?, ?>>> handlers = getHandlersForMessage(interceptedQuery);
        try {
            if (handlers.isEmpty()) {
                throw new NoHandlerForQueryException(format("No handler found for %s with response type %s",
                                                            interceptedQuery.getQueryName(),
                                                            interceptedQuery.getResponseType()));
            }
            Iterator<MessageHandler<? super QueryMessage<?, ?>>> handlerIterator = handlers.iterator();
            boolean invocationSuccess = false;
            QueryResponseMessage<R> result = null;
            while (!invocationSuccess && handlerIterator.hasNext()) {
                try {
                    DefaultUnitOfWork<QueryMessage<Q, R>> uow = DefaultUnitOfWork.startAndGet(interceptedQuery);
                    result = GenericQueryResponseMessage.asResponseMessage(
                            interceptAndInvoke(uow, handlerIterator.next())
                    );
                    invocationSuccess = true;
                } catch (NoHandlerForQueryException e) {
                    // Ignore this Query Handler, as we may have another one which is suitable
                }
            }
            if (!invocationSuccess) {
                throw new NoHandlerForQueryException(format("No suitable handler was found for %s with response type %s",
                                                            interceptedQuery.getQueryName(),
                                                            interceptedQuery.getResponseType()));
            }
            completableFuture.complete(result);
            monitorCallback.reportSuccess();
        } catch (Exception e) {
            completableFuture.completeExceptionally(e);
            monitorCallback.reportFailure(e);
        }
        return completableFuture;
    }

    @Override
    public <Q, R> Stream<QueryResponseMessage<R>> scatterGather(QueryMessage<Q, R> query, long timeout, TimeUnit unit) {
        MessageMonitor.MonitorCallback monitorCallback = messageMonitor.onMessageIngested(query);
        QueryMessage<Q, R> interceptedQuery = intercept(query);
        List<MessageHandler<? super QueryMessage<?, ?>>> handlers = getHandlersForMessage(interceptedQuery);
        if (handlers.isEmpty()) {
            monitorCallback.reportIgnored();
            return Stream.empty();
        }

        return handlers.stream()
                       .map(mh -> {
                           QueryResponseMessage<R> result = null;
                           try {
                               result = interceptAndInvoke(DefaultUnitOfWork.startAndGet(interceptedQuery), mh);
                               monitorCallback.reportSuccess();
                               return result;
                           } catch (Exception e) {
                               monitorCallback.reportFailure(e);
                               errorHandler.onError(e, interceptedQuery, mh);
                           }
                           return result;
                       })
                       .filter(Objects::nonNull);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <Q, I, U> Registration subscriptionQuery(SubscriptionQueryMessage<Q, I, U> query,
                                                    UpdateHandler<I, U> updateHandler) {
        MessageMonitor.MonitorCallback monitorCallback = messageMonitor.onMessageIngested(query);
        SubscriptionQueryMessage<Q, I, U> interceptedQuery = intercept(query);
        List<QuerySubscription> subs = subscriptions
                .computeIfAbsent(interceptedQuery.getQueryName(), k -> new CopyOnWriteArrayList<>())
                .stream()
                .filter(subscription -> interceptedQuery.getResponseType().matches(subscription.getResponseType()))
                .collect(Collectors.toList());
        Registration registration = () -> true;
        try {
            if (subs.isEmpty()) {
                throw new NoHandlerForQueryException(
                        format("No handler found for %s with response type %s and update type %s",
                               interceptedQuery.getQueryName(),
                               interceptedQuery.getResponseType(),
                               interceptedQuery.getUpdateResponseType()));
            }
            Iterator<QuerySubscription> subsIterator = subs.iterator();
            boolean invocationSuccess = false;
            while (!invocationSuccess && subsIterator.hasNext()) {
                try {
                    QuerySubscription subscription = subsIterator.next();
                    DefaultUnitOfWork<QueryMessage<Q, I>> uow = DefaultUnitOfWork.startAndGet(interceptedQuery);
                    if (subscription instanceof SubscribableQuerySubscription) {
                        if (interceptedQuery.getUpdateResponseType()
                                            .matches(((SubscribableQuerySubscription) subscription).getUpdateType())) {

                            registration = invokeSubscriptionQueryHandler(uow,
                                                                          ((SubscribableQuerySubscription) subscription)
                                                                                  .getSubscriptionQueryHandler(),
                                                                          updateHandler);
                            invocationSuccess = true;
                        }
                    } else {
                        invokeRegularQueryHandler(uow, subscription.getQueryHandler(), updateHandler);
                        invocationSuccess = true;
                    }
                } catch (NoHandlerForQueryException e) {
                    // Ignore this Query Handler, as we may have another one which is suitable
                }
            }
            if (!invocationSuccess) {
                throw new NoHandlerForQueryException(
                        format("No suitable handler was found for %s with response type %s and update type %s",
                               interceptedQuery.getQueryName(),
                               interceptedQuery.getResponseType(),
                               interceptedQuery.getUpdateResponseType()));
            }
        } catch (Exception e) {
            monitorCallback.reportFailure(e);
            updateHandler.onError(e);
        }

        return registration;
    }

    /**
     * Invokes regular query handler and completes the update handler right afterwards.
     *
     * @param uow           the Unit of Work in which the query handler will be invoked
     * @param queryHandler  the query handler to be invoked
     * @param updateHandler the update handler to be invoked with result of query handler
     * @param <Q>           the query type
     * @param <I>           the initial result type
     * @throws Exception propagated from query handler
     */
    @SuppressWarnings("unchecked")
    private <Q, I> void invokeRegularQueryHandler(UnitOfWork<QueryMessage<Q, I>> uow,
                                                     MessageHandler<? super QueryMessage<?, I>> queryHandler,
                                                     UpdateHandler<I, ?> updateHandler) throws Exception {
        I initialResult = interceptAndInvoke(uow, queryHandler).getPayload();
        updateHandler.onInitialResult(initialResult);
        updateHandler.onCompleted();
    }

    /**
     * Invokes subscription query handler with freshly initialized emitter.
     *
     * @param uow           the Unit of Work in which the query handler will be invoked
     * @param queryHandler  the query handler to be invoked
     * @param updateHandler the update handler to be invoked with result of query handler
     * @param <Q>           the query type
     * @param <I>           the initial result type
     * @param <U>           the incremental update type
     * @return handle to cancel updates on this query
     *
     * @throws Exception propagated from query handler
     */
    @SuppressWarnings("unchecked")
    private <Q, I, U> Registration invokeSubscriptionQueryHandler(UnitOfWork<QueryMessage<Q, I>> uow,
                                                                  SubscriptionQueryMessageHandler queryHandler,
                                                                  UpdateHandler<I, U> updateHandler)
            throws Exception {
        ReentrantLock initialLock = new ReentrantLock(true);
        Condition initialCondition = initialLock.newCondition();
        SimpleQueryUpdateEmitter emitter = new SimpleQueryUpdateEmitter(updateHandler, initialLock, initialCondition);
        I initialResult = interceptAndInvoke(uow, m -> queryHandler.handle(m, emitter)).getPayload();
        initialLock.lock();
        try {
            updateHandler.onInitialResult(initialResult);
        } finally {
            emitter.initialInvoked();
            initialCondition.signalAll();
            initialLock.unlock();
        }
        return () -> {
            emitter.cancelRegistration();
            return true;
        };
    }

    @SuppressWarnings("unchecked")
    private <Q, R> QueryResponseMessage<R> interceptAndInvoke(UnitOfWork<QueryMessage<Q, R>> uow,
                                                              MessageHandler<? super QueryMessage<?, R>> handler)
            throws Exception {
        return uow.executeWithResult(() -> {
            ResponseType<R> responseType = uow.getMessage().getResponseType();
            Object queryResponse = new DefaultInterceptorChain<>(uow, handlerInterceptors, handler).proceed();
            return GenericQueryResponseMessage.asResponseMessage(responseType.convert(queryResponse));
        });
    }

    @SuppressWarnings("unchecked")
    private <Q, R, T extends QueryMessage<Q, R>> T intercept(T query) {
        T intercepted = query;
        for (MessageDispatchInterceptor<? super QueryMessage<?, ?>> interceptor : dispatchInterceptors) {
            intercepted = (T) interceptor.handle(intercepted);
        }
        return intercepted;
    }

    /**
     * Returns the subscriptions for this query bus. While the returned map is unmodifiable, it may or may not reflect
     * changes made to the subscriptions after the call was made.
     *
     * @return the subscriptions for this query bus
     */
    protected Map<String, Collection<QuerySubscription>> getSubscriptions() {
        return Collections.unmodifiableMap(subscriptions);
    }

    /**
     * Registers an interceptor that is used to intercept Queries before they are passed to their
     * respective handlers. The interceptor is invoked separately for each handler instance (in a separate unit of work).
     *
     * @param interceptor the interceptor to invoke before passing a Query to the handler
     * @return handle to unregister the interceptor
     */
    public Registration registerHandlerInterceptor(MessageHandlerInterceptor<? super QueryMessage<?, ?>> interceptor) {
        handlerInterceptors.add(interceptor);
        return () -> handlerInterceptors.remove(interceptor);
    }

    /**
     * Registers an interceptor that intercepts Queries as they are sent. Each interceptor is called
     * once, regardless of the type of query (point-to-point or scatter-gather) executed.
     *
     * @param interceptor the interceptor to invoke when sending a Query
     * @return handle to unregister the interceptor
     */
    public Registration registerDispatchInterceptor(MessageDispatchInterceptor<? super QueryMessage<?, ?>> interceptor) {
        dispatchInterceptors.add(interceptor);
        return () -> dispatchInterceptors.remove(interceptor);
    }

    @SuppressWarnings("unchecked") // Suppresses 'queryHandler' cast to `MessageHandler<? super QueryMessage<?, ?>>`
    private <Q, R> List<MessageHandler<? super QueryMessage<?, ?>>> getHandlersForMessage(
            QueryMessage<Q, R> queryMessage) {
        ResponseType<R> responseType = queryMessage.getResponseType();
        return subscriptions.computeIfAbsent(queryMessage.getQueryName(), k -> new CopyOnWriteArrayList<>())
                            .stream()
                            .filter(querySubscription -> responseType.matches(querySubscription.getResponseType()))
                            .map((Function<QuerySubscription, MessageHandler>) QuerySubscription::getQueryHandler)
                            .map(queryHandler -> (MessageHandler<? super QueryMessage<?, ?>>) queryHandler)
                            .collect(Collectors.toList());
    }

    private class SimpleQueryUpdateEmitter<U> implements QueryUpdateEmitter<U> {

        private UpdateHandler<?, U> updateHandler;
        private final AtomicReference<Runnable> registrationCanceledHandlerRef = new AtomicReference<>();
        private volatile boolean active;
        private final ReentrantReadWriteLock producerConsumerLock = new ReentrantReadWriteLock(true);
        private final ReentrantLock initialLock;
        private final Condition initialCondition;
        private volatile boolean initial;

        SimpleQueryUpdateEmitter(UpdateHandler<?, U> updateHandler, ReentrantLock initialLock,
                                 Condition initialCondition) {
            this.updateHandler = updateHandler;
            this.initialLock = initialLock;
            this.initialCondition = initialCondition;
            this.active = true;
            this.initial = true;
        }

        @Override
        public boolean emit(U update) {
            if (!waitForInitial()) {
                return false;
            }
            ensureActive();
            return readLockSafe(() -> updateHandler.onUpdate(update));
        }

        @Override
        public boolean complete() {
            if (!waitForInitial()) {
                return false;
            }
            active = false;
            return readLockSafe(() -> updateHandler.onCompleted());
        }

        @Override
        public boolean error(Throwable error) {
            if (!waitForInitial()) {
                return false;
            }
            ensureActive();
            return readLockSafe(() -> updateHandler.onError(error));
        }

        @Override
        public void onRegistrationCanceled(Runnable r) {
            registrationCanceledHandlerRef.set(r);
        }

        private void cancelRegistration() {
            producerConsumerLock.writeLock().lock();
            updateHandler = null;
            invokeRegistrationCanceledHandler();
            producerConsumerLock.writeLock().unlock();
        }

        private boolean waitForInitial() {
            if (initial) {
                initialLock.lock();
                try {
                    initialCondition.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } finally {
                    initialInvoked();
                    initialLock.unlock();
                }
            }
            return true;
        }

        private void initialInvoked() {
            this.initial = false;
        }

        private boolean readLockSafe(Runnable r) {
            producerConsumerLock.readLock().lock();
            try {
                if (updateHandler == null) {
                    return false;
                }
                r.run();
                return true;
            } finally {
                producerConsumerLock.readLock().unlock();
            }
        }

        private void ensureActive() {
            if (!active) {
                throw new CompletedEmitterException("This emitter has already completed emitting updates. "
                                + "There should be no interaction with emitter after calling QueryUpdateEmitter#complete.");
            }
        }

        private void invokeRegistrationCanceledHandler() {
            Runnable registrationCanceledHandler = registrationCanceledHandlerRef.get();
            if (registrationCanceledHandler != null) {
                registrationCanceledHandler.run();
            }
        }
    }
}
