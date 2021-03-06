/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.cache;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.async.methods.SimpleBody;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.cache.CacheResponseStatus;
import org.apache.hc.client5.http.cache.HeaderConstants;
import org.apache.hc.client5.http.cache.HttpAsyncCacheStorage;
import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.client5.http.cache.ResourceFactory;
import org.apache.hc.client5.http.cache.ResourceIOException;
import org.apache.hc.client5.http.impl.RequestCopier;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.concurrent.ComplexFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.impl.BasicEntityDetails;
import org.apache.hc.core5.http.nio.AsyncDataConsumer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.ByteArrayBuffer;

/**
 * Request executor in the request execution chain that is responsible for
 * transparent client-side caching.
 * <p>
 * The current implementation is conditionally
 * compliant with HTTP/1.1 (meaning all the MUST and MUST NOTs are obeyed),
 * although quite a lot, though not all, of the SHOULDs and SHOULD NOTs
 * are obeyed too.
 *
 * @since 5.0
 */
@Contract(threading = ThreadingBehavior.SAFE) // So long as the responseCache implementation is threadsafe
public class AsyncCachingExec extends CachingExecBase implements AsyncExecChainHandler {

    private final HttpAsyncCache responseCache;
    private final ConditionalRequestBuilder<HttpRequest> conditionalRequestBuilder;

    public AsyncCachingExec(final HttpAsyncCache cache, final CacheConfig config) {
        super(config);
        this.responseCache = Args.notNull(cache, "Response cache");
        this.conditionalRequestBuilder = new ConditionalRequestBuilder<>(RequestCopier.INSTANCE);
    }

    public AsyncCachingExec(
            final ResourceFactory resourceFactory,
            final HttpAsyncCacheStorage storage,
            final CacheConfig config) {
        this(new BasicHttpAsyncCache(resourceFactory, storage), config);
    }

    AsyncCachingExec(
            final HttpAsyncCache responseCache,
            final CacheValidityPolicy validityPolicy,
            final ResponseCachingPolicy responseCachingPolicy,
            final CachedHttpResponseGenerator responseGenerator,
            final CacheableRequestPolicy cacheableRequestPolicy,
            final CachedResponseSuitabilityChecker suitabilityChecker,
            final ConditionalRequestBuilder<HttpRequest> conditionalRequestBuilder,
            final ResponseProtocolCompliance responseCompliance,
            final RequestProtocolCompliance requestCompliance,
            final CacheConfig config) {
        super(validityPolicy, responseCachingPolicy, responseGenerator, cacheableRequestPolicy,
                suitabilityChecker, responseCompliance, requestCompliance, config);
        this.responseCache = responseCache;
        this.conditionalRequestBuilder = conditionalRequestBuilder;
    }

    private void triggerResponse(
            final SimpleHttpResponse cacheResponse,
            final AsyncExecChain.Scope scope,
            final AsyncExecCallback asyncExecCallback) {
        scope.clientContext.setAttribute(HttpCoreContext.HTTP_RESPONSE, cacheResponse);
        scope.execRuntime.releaseConnection();

        final SimpleBody body = cacheResponse.getBody();
        final byte[] content = body != null ? body.getBodyBytes() : null;
        final ContentType contentType = body != null ? body.getContentType() : null;
        try {
            final AsyncDataConsumer dataConsumer = asyncExecCallback.handleResponse(
                    cacheResponse,
                    content != null ? new BasicEntityDetails(content.length, contentType) : null);
            if (dataConsumer != null) {
                dataConsumer.consume(ByteBuffer.wrap(content));
                dataConsumer.streamEnd(null);
            }
            asyncExecCallback.completed();
        } catch (final HttpException | IOException ex) {
            asyncExecCallback.failed(ex);
        }
    }

    @Override
    public void execute(
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        Args.notNull(scope, "Scope");

        final HttpRoute route = scope.route;
        final ComplexFuture<?> future = scope.future;
        final HttpClientContext context = scope.clientContext;
        context.setAttribute(HttpClientContext.HTTP_ROUTE, route);
        context.setAttribute(HttpClientContext.HTTP_REQUEST, request);

        final URIAuthority authority = request.getAuthority();
        final String scheme = request.getScheme();
        final HttpHost target = authority != null ? new HttpHost(authority, scheme) : route.getTargetHost();
        final String via = generateViaHeader(request);

        // default response context
        setResponseStatus(context, CacheResponseStatus.CACHE_MISS);

        if (clientRequestsOurOptions(request)) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            triggerResponse(SimpleHttpResponse.create(HttpStatus.SC_NOT_IMPLEMENTED), scope, asyncExecCallback);
            return;
        }

        final SimpleHttpResponse fatalErrorResponse = getFatallyNoncompliantResponse(request, context);
        if (fatalErrorResponse != null) {
            triggerResponse(fatalErrorResponse, scope, asyncExecCallback);
            return;
        }

        requestCompliance.makeRequestCompliant(request);
        request.addHeader("Via",via);

        if (!cacheableRequestPolicy.isServableFromCache(request)) {
            log.debug("Request is not servable from cache");
            future.setDependency(responseCache.flushInvalidatedCacheEntriesFor(target, request, new FutureCallback<Boolean>() {

                @Override
                public void completed(final Boolean result) {
                    callBackend(target, request, entityProducer, scope, chain, asyncExecCallback);
                }

                @Override
                public void failed(final Exception cause) {
                    asyncExecCallback.failed(cause);
                }

                @Override
                public void cancelled() {
                    asyncExecCallback.failed(new InterruptedIOException());
                }

            }));
        } else {
            future.setDependency(responseCache.getCacheEntry(target, request, new FutureCallback<HttpCacheEntry>() {

                @Override
                public void completed(final HttpCacheEntry entry) {
                    if (entry == null) {
                        log.debug("Cache miss");
                        handleCacheMiss(target, request, entityProducer, scope, chain, asyncExecCallback);
                    } else {
                        handleCacheHit(target, request, entityProducer, scope, chain, asyncExecCallback, entry);
                    }
                }

                @Override
                public void failed(final Exception cause) {
                    asyncExecCallback.failed(cause);
                }

                @Override
                public void cancelled() {
                    asyncExecCallback.failed(new InterruptedIOException());
                }

            }));

        }
    }

    interface InternalCallback extends AsyncExecCallback {

        boolean cacheResponse(HttpResponse backendResponse) throws HttpException, IOException;

    }

    void callBackend(
            final HttpHost target,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) {
        callBackendInternal(target, request, entityProducer, scope, chain, new InternalCallback() {

            @Override
            public boolean cacheResponse(final HttpResponse backendResponse) {
                return true;
            }

            @Override
            public AsyncDataConsumer handleResponse(
                    final HttpResponse response, final EntityDetails entityDetails) throws HttpException, IOException {
                return asyncExecCallback.handleResponse(response, entityDetails);
            }

            @Override
            public void completed() {
                asyncExecCallback.completed();
            }

            @Override
            public void failed(final Exception cause) {
                asyncExecCallback.failed(cause);
            }

        });
    }

    static class ResponseState {

        final HttpResponse backendResponse;
        final Date responseDate;
        final ByteArrayBuffer buffer;

        ResponseState(final HttpResponse backendResponse, final Date responseDate, final ByteArrayBuffer buffer) {
            this.backendResponse = backendResponse;
            this.responseDate = responseDate;
            this.buffer = buffer;
        }

    }

    void callBackendInternal(
            final HttpHost target,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final InternalCallback asyncExecCallback) {
        log.debug("Calling the backend");
        final ComplexFuture<?> future = scope.future;
        final Date requestDate = getCurrentDate();
        try {
            chain.proceed(request, entityProducer, scope, new AsyncExecCallback() {

                private final AtomicReference<ResponseState> responseStateRef = new AtomicReference<>();
                private final AtomicReference<AsyncDataConsumer> dataConsumerRef = new AtomicReference<>();

                @Override
                public AsyncDataConsumer handleResponse(
                        final HttpResponse backendResponse,
                        final EntityDetails entityDetails) throws HttpException, IOException {
                    final Date responseDate = getCurrentDate();
                    backendResponse.addHeader("Via", generateViaHeader(backendResponse));

                    responseCompliance.ensureProtocolCompliance(scope.originalRequest, request, backendResponse);
                    responseCache.flushInvalidatedCacheEntriesFor(target, request, backendResponse, new FutureCallback<Boolean>() {

                        @Override
                        public void completed(final Boolean result) {
                        }

                        @Override
                        public void failed(final Exception ex) {
                            log.warn("Unable to flush invalidated entries from cache", ex);
                        }

                        @Override
                        public void cancelled() {
                        }

                    });
                    final boolean cacheable = asyncExecCallback.cacheResponse(backendResponse)
                            && responseCachingPolicy.isResponseCacheable(request, backendResponse);
                    if (cacheable) {
                        responseStateRef.set(new ResponseState(
                                backendResponse,
                                responseDate,
                                entityDetails != null ? new ByteArrayBuffer(1024) : null));
                        storeRequestIfModifiedSinceFor304Response(request, backendResponse);
                    } else {
                        log.debug("Backend response is not cacheable");
                        responseCache.flushCacheEntriesFor(target, request, new FutureCallback<Boolean>() {

                            @Override
                            public void completed(final Boolean result) {
                            }

                            @Override
                            public void failed(final Exception ex) {
                                log.warn("Unable to flush invalidated entries from cache", ex);
                            }

                            @Override
                            public void cancelled() {
                            }

                        });
                    }
                    if (responseStateRef.get() != null) {
                        log.debug("Caching backend response");
                        if (entityDetails == null) {
                            scope.execRuntime.releaseConnection();
                            return null;
                        } else {
                            return new AsyncDataConsumer() {

                                @Override
                                public final void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
                                    final AsyncDataConsumer dataConsumer = dataConsumerRef.get();
                                    if (dataConsumer != null) {
                                        dataConsumer.updateCapacity(capacityChannel);
                                    } else {
                                        capacityChannel.update(Integer.MAX_VALUE);
                                    }
                                }

                                @Override
                                public final int consume(final ByteBuffer src) throws IOException {
                                    final ResponseState responseState = responseStateRef.get();
                                    if (responseState != null) {
                                        final ByteArrayBuffer buffer = responseState.buffer;
                                        if (src.hasArray()) {
                                            buffer.append(src.array(), src.arrayOffset() + src.position(), src.remaining());
                                        } else {
                                            while (src.hasRemaining()) {
                                                buffer.append(src.get());
                                            }
                                        }
                                        if (buffer.length() > cacheConfig.getMaxObjectSize()) {
                                            log.debug("Backend response content length exceeds maximum");
                                            // Over the max limit. Stop buffering and forward the response
                                            // along with all the data buffered so far to the caller.
                                            responseStateRef.set(null);
                                            try {
                                                final AsyncDataConsumer dataConsumer = asyncExecCallback.handleResponse(
                                                        backendResponse, entityDetails);
                                                if (dataConsumer != null) {
                                                    dataConsumerRef.set(dataConsumer);
                                                    return dataConsumer.consume(ByteBuffer.wrap(buffer.array(), 0, buffer.length()));
                                                }
                                            } catch (final HttpException ex) {
                                                asyncExecCallback.failed(ex);
                                            }
                                        }
                                        return Integer.MAX_VALUE;
                                    } else {
                                        final AsyncDataConsumer dataConsumer = dataConsumerRef.get();
                                        if (dataConsumer != null) {
                                            return dataConsumer.consume(src);
                                        } else {
                                            return Integer.MAX_VALUE;
                                        }
                                    }
                                }

                                @Override
                                public final void streamEnd(final List<? extends Header> trailers) throws HttpException, IOException {
                                    scope.execRuntime.releaseConnection();
                                    final AsyncDataConsumer dataConsumer = dataConsumerRef.getAndSet(null);
                                    if (dataConsumer != null) {
                                        dataConsumer.streamEnd(trailers);
                                    }
                                }

                                @Override
                                public void releaseResources() {
                                    final AsyncDataConsumer dataConsumer = dataConsumerRef.getAndSet(null);
                                    if (dataConsumer != null) {
                                        dataConsumer.releaseResources();
                                    }
                                }

                            };
                        }
                    } else {
                        return asyncExecCallback.handleResponse(backendResponse, entityDetails);
                    }
                }

                @Override
                public void completed() {
                    final ResponseState responseState = responseStateRef.getAndSet(null);
                    if (responseState != null) {
                        future.setDependency(responseCache.getCacheEntry(target, request, new FutureCallback<HttpCacheEntry>() {

                            @Override
                            public void completed(final HttpCacheEntry existingEntry) {
                                final HttpResponse backendResponse = responseState.backendResponse;
                                if (DateUtils.isAfter(existingEntry, backendResponse, HttpHeaders.DATE)) {
                                    try {
                                        final SimpleHttpResponse cacheResponse = responseGenerator.generateResponse(request, existingEntry);
                                        triggerResponse(cacheResponse, scope, asyncExecCallback);
                                    } catch (final ResourceIOException ex) {
                                        asyncExecCallback.failed(ex);
                                    }
                                } else {
                                    final Date responseDate = responseState.responseDate;
                                    final ByteArrayBuffer buffer = responseState.buffer;
                                    future.setDependency(responseCache.createCacheEntry(
                                            target,
                                            request,
                                            backendResponse,
                                            buffer,
                                            requestDate,
                                            responseDate,
                                            new FutureCallback<HttpCacheEntry>() {

                                                @Override
                                                public void completed(final HttpCacheEntry newEntry) {
                                                    log.debug("Backend response successfully cached");
                                                    try {
                                                        final SimpleHttpResponse cacheResponse = responseGenerator.generateResponse(request, newEntry);
                                                        triggerResponse(cacheResponse, scope, asyncExecCallback);
                                                    } catch (final ResourceIOException ex) {
                                                        asyncExecCallback.failed(ex);
                                                    }
                                                }

                                                @Override
                                                public void failed(final Exception ex) {
                                                    asyncExecCallback.failed(ex);
                                                }

                                                @Override
                                                public void cancelled() {
                                                    asyncExecCallback.failed(new InterruptedIOException());
                                                }

                                            }));

                                }
                            }

                            @Override
                            public void failed(final Exception cause) {
                                asyncExecCallback.failed(cause);
                            }

                            @Override
                            public void cancelled() {
                                asyncExecCallback.failed(new InterruptedIOException());
                            }

                        }));
                    } else {
                        asyncExecCallback.completed();
                    }
                }

                @Override
                public void failed(final Exception cause) {
                    asyncExecCallback.failed(cause);
                }

            });
        } catch (final HttpException | IOException ex) {
            asyncExecCallback.failed(ex);
        }
    }

    private void handleCacheHit(
            final HttpHost target,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback,
            final HttpCacheEntry entry) {
        final HttpClientContext context  = scope.clientContext;
        recordCacheHit(target, request);
        final Date now = getCurrentDate();
        if (suitabilityChecker.canCachedResponseBeUsed(target, request, entry, now)) {
            log.debug("Cache hit");
            try {
                final SimpleHttpResponse cacheResponse = generateCachedResponse(request, context, entry, now);
                triggerResponse(cacheResponse, scope, asyncExecCallback);
            } catch (final ResourceIOException ex) {
                recordCacheFailure(target, request);
                if (!mayCallBackend(request)) {
                    final SimpleHttpResponse cacheResponse = generateGatewayTimeout(context);
                    triggerResponse(cacheResponse, scope, asyncExecCallback);
                } else {
                    setResponseStatus(scope.clientContext, CacheResponseStatus.FAILURE);
                    try {
                        chain.proceed(request, entityProducer, scope, asyncExecCallback);
                    } catch (final HttpException | IOException ex2) {
                        asyncExecCallback.failed(ex2);
                    }
                }
            }
        } else if (!mayCallBackend(request)) {
            log.debug("Cache entry not suitable but only-if-cached requested");
            final SimpleHttpResponse cacheResponse = generateGatewayTimeout(context);
            triggerResponse(cacheResponse, scope, asyncExecCallback);
        } else if (!(entry.getStatus() == HttpStatus.SC_NOT_MODIFIED && !suitabilityChecker.isConditional(request))) {
            log.debug("Revalidating cache entry");
            revalidateCacheEntry(target, request, entityProducer, scope, chain, asyncExecCallback, entry);
        } else {
            log.debug("Cache entry not usable; calling backend");
            callBackend(target, request, entityProducer, scope, chain, asyncExecCallback);
        }
    }

    void revalidateCacheEntry(
            final HttpHost target,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback,
            final HttpCacheEntry cacheEntry) {

        final ComplexFuture<?> future = scope.future;
        final Date requestDate = getCurrentDate();
        final InternalCallback internalCallback = new InternalCallback() {

            private final AtomicReference<Date> responseDateRef = new AtomicReference<>(null);
            private final AtomicReference<HttpResponse> backendResponseRef = new AtomicReference<>(null);

            @Override
            public boolean cacheResponse(final HttpResponse backendResponse) throws IOException {
                final Date responseDate = getCurrentDate();
                responseDateRef.set(requestDate);
                final int statusCode = backendResponse.getCode();
                if (statusCode == HttpStatus.SC_NOT_MODIFIED || statusCode == HttpStatus.SC_OK) {
                    recordCacheUpdate(scope.clientContext);
                }
                if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
                    backendResponseRef.set(backendResponse);
                    return false;
                }
                if (staleIfErrorAppliesTo(statusCode)
                        && !staleResponseNotAllowed(request, cacheEntry, getCurrentDate())
                        && validityPolicy.mayReturnStaleIfError(request, cacheEntry, responseDate)) {
                    backendResponseRef.set(backendResponse);
                    return false;
                }
                return true;
            }

            @Override
            public AsyncDataConsumer handleResponse(
                    final HttpResponse response, final EntityDetails entityDetails) throws HttpException, IOException {
                if (backendResponseRef.get() == null) {
                    return asyncExecCallback.handleResponse(response, entityDetails);
                } else {
                    return null;
                }
            }

            @Override
            public void completed() {
                final HttpResponse backendResponse = backendResponseRef.getAndSet(null);
                if (backendResponse != null) {
                    final int statusCode = backendResponse.getCode();
                    try {
                        if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
                            future.setDependency(responseCache.updateCacheEntry(
                                    target,
                                    request,
                                    cacheEntry,
                                    backendResponse,
                                    requestDate,
                                    responseDateRef.get(),
                                    new FutureCallback<HttpCacheEntry>() {

                                        @Override
                                        public void completed(final HttpCacheEntry updatedEntry) {
                                            if (suitabilityChecker.isConditional(request)
                                                    && suitabilityChecker.allConditionalsMatch(request, updatedEntry, new Date())) {
                                                final SimpleHttpResponse cacheResponse = responseGenerator.generateNotModifiedResponse(updatedEntry);
                                                triggerResponse(cacheResponse, scope, asyncExecCallback);
                                            } else {
                                                try {
                                                    final SimpleHttpResponse cacheResponse = responseGenerator.generateResponse(request, updatedEntry);
                                                    triggerResponse(cacheResponse, scope, asyncExecCallback);
                                                } catch (final ResourceIOException ex) {
                                                    asyncExecCallback.failed(ex);
                                                }
                                            }
                                        }

                                        @Override
                                        public void failed(final Exception ex) {
                                            asyncExecCallback.failed(ex);
                                        }

                                        @Override
                                        public void cancelled() {
                                            asyncExecCallback.failed(new InterruptedIOException());
                                        }

                                    }));
                        } else if (staleIfErrorAppliesTo(statusCode)) {
                            final SimpleHttpResponse cacheResponse = responseGenerator.generateResponse(request, cacheEntry);
                            cacheResponse.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");
                            triggerResponse(cacheResponse, scope, asyncExecCallback);
                        }
                    } catch (final IOException ex) {
                        asyncExecCallback.failed(ex);
                    }
                } else {
                    asyncExecCallback.completed();
                }
            }

            @Override
            public void failed(final Exception cause) {
                asyncExecCallback.failed(cause);
            }

        };

        final HttpRequest conditionalRequest = conditionalRequestBuilder.buildConditionalRequest(scope.originalRequest, cacheEntry);
        callBackendInternal(target, conditionalRequest, entityProducer, scope, chain, new InternalCallback() {

            private final AtomicBoolean revalidate = new AtomicBoolean(false);

            @Override
            public boolean cacheResponse(final HttpResponse backendResponse) throws HttpException, IOException {
                if (revalidationResponseIsTooOld(backendResponse, cacheEntry)) {
                    revalidate.set(true);
                    return false;
                } else {
                    return internalCallback.cacheResponse(backendResponse);
                }
            }

            @Override
            public AsyncDataConsumer handleResponse(
                    final HttpResponse response,
                    final EntityDetails entityDetails) throws HttpException, IOException {
                if (revalidate.get()) {
                    return null;
                } else {
                    return internalCallback.handleResponse(response, entityDetails);
                }
            }

            @Override
            public void completed() {
                if (revalidate.getAndSet(false)) {
                    final HttpRequest unconditionalRequest = conditionalRequestBuilder.buildUnconditionalRequest(scope.originalRequest);
                    callBackendInternal(target, unconditionalRequest, entityProducer, scope, chain, new InternalCallback() {

                        @Override
                        public boolean cacheResponse(final HttpResponse backendResponse) throws HttpException, IOException {
                            return internalCallback.cacheResponse(backendResponse);
                        }

                        @Override
                        public AsyncDataConsumer handleResponse(
                                final HttpResponse response, final EntityDetails entityDetails) throws HttpException, IOException {
                            return internalCallback.handleResponse(response, entityDetails);
                        }

                        @Override
                        public void completed() {
                            internalCallback.completed();
                        }

                        @Override
                        public void failed(final Exception cause) {
                            internalCallback.failed(cause);
                        }

                    });
                } else {
                    internalCallback.completed();
                }
            }

            @Override
            public void failed(final Exception cause) {
                internalCallback.failed(cause);
            }

        });

    }

    private void handleCacheMiss(
            final HttpHost target,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) {
        recordCacheMiss(target, request);

        if (mayCallBackend(request)) {
            final ComplexFuture<?> future = scope.future;
            future.setDependency(responseCache.getVariantCacheEntriesWithEtags(
                    target,
                    request,
                    new FutureCallback<Map<String, Variant>>() {

                        @Override
                        public void completed(final Map<String, Variant> variants) {
                            if (variants != null && !variants.isEmpty()) {
                                negotiateResponseFromVariants(target, request, entityProducer, scope, chain, asyncExecCallback, variants);
                            } else {
                                callBackend(target, request, entityProducer, scope, chain, asyncExecCallback);
                            }
                        }

                        @Override
                        public void failed(final Exception ex) {
                            asyncExecCallback.failed(ex);
                        }

                        @Override
                        public void cancelled() {
                            asyncExecCallback.failed(new InterruptedIOException());
                        }

                    }));
        } else {
            final SimpleHttpResponse cacheResponse = SimpleHttpResponse.create(HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
            triggerResponse(cacheResponse, scope, asyncExecCallback);
        }
    }

    void negotiateResponseFromVariants(
            final HttpHost target,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback,
            final Map<String, Variant> variants) {
        final ComplexFuture<?> future = scope.future;
        final HttpRequest conditionalRequest = conditionalRequestBuilder.buildConditionalRequestFromVariants(request, variants);

        final Date requestDate = getCurrentDate();
        callBackendInternal(target, conditionalRequest, entityProducer, scope, chain, new InternalCallback() {

            private final AtomicReference<Date> responseDateRef = new AtomicReference<>(null);
            private final AtomicReference<HttpResponse> backendResponseRef = new AtomicReference<>(null);

            @Override
            public boolean cacheResponse(final HttpResponse backendResponse) throws IOException {
                responseDateRef.set(getCurrentDate());
                if (backendResponse.getCode() == HttpStatus.SC_NOT_MODIFIED) {
                    backendResponseRef.set(backendResponse);
                    return false;
                } else {
                    return true;
                }
            }

            @Override
            public AsyncDataConsumer handleResponse(
                    final HttpResponse response, final EntityDetails entityDetails) throws HttpException, IOException {
                return asyncExecCallback.handleResponse(response, entityDetails);
            }

            @Override
            public void completed() {
                final HttpResponse backendResponse = backendResponseRef.getAndSet(null);
                if (backendResponse != null) {
                    final Header resultEtagHeader = backendResponse.getFirstHeader(HeaderConstants.ETAG);
                    if (resultEtagHeader == null) {
                        log.warn("304 response did not contain ETag");
                        callBackend(target, request, entityProducer, scope, chain, asyncExecCallback);
                        return;
                    }
                    final String resultEtag = resultEtagHeader.getValue();
                    final Variant matchingVariant = variants.get(resultEtag);
                    if (matchingVariant == null) {
                        log.debug("304 response did not contain ETag matching one sent in If-None-Match");
                        callBackend(target, request, entityProducer, scope, chain, asyncExecCallback);
                        return;
                    }
                    final HttpCacheEntry matchedEntry = matchingVariant.getEntry();
                    if (revalidationResponseIsTooOld(backendResponse, matchedEntry)) {
                        final HttpRequest unconditional = conditionalRequestBuilder.buildUnconditionalRequest(request);
                        scope.clientContext.setAttribute(HttpCoreContext.HTTP_REQUEST, unconditional);
                        callBackend(target, unconditional, entityProducer, scope, chain, asyncExecCallback);
                        return;
                    }
                    recordCacheUpdate(scope.clientContext);
                    future.setDependency(responseCache.updateVariantCacheEntry(
                            target,
                            conditionalRequest,
                            matchedEntry,
                            backendResponse,
                            requestDate,
                            responseDateRef.get(),
                            matchingVariant.getCacheKey(),
                            new FutureCallback<HttpCacheEntry>() {

                                @Override
                                public void completed(final HttpCacheEntry responseEntry) {
                                    if (shouldSendNotModifiedResponse(request, responseEntry)) {
                                        final SimpleHttpResponse cacheResponse = responseGenerator.generateNotModifiedResponse(responseEntry);
                                        triggerResponse(cacheResponse, scope, asyncExecCallback);
                                    } else {
                                        try {
                                            final SimpleHttpResponse cacheResponse = responseGenerator.generateResponse(request, responseEntry);
                                            future.setDependency(responseCache.reuseVariantEntryFor(
                                                    target,
                                                    request,
                                                    matchingVariant,
                                                    new FutureCallback<Boolean>() {

                                                        @Override
                                                        public void completed(final Boolean result) {
                                                            triggerResponse(cacheResponse, scope, asyncExecCallback);
                                                        }

                                                        @Override
                                                        public void failed(final Exception ex) {
                                                            asyncExecCallback.failed(ex);
                                                        }

                                                        @Override
                                                        public void cancelled() {
                                                            asyncExecCallback.failed(new InterruptedIOException());
                                                        }

                                                    }));
                                        } catch (final ResourceIOException ex) {
                                            asyncExecCallback.failed(ex);
                                        }
                                    }
                                }

                                @Override
                                public void failed(final Exception ex) {
                                    asyncExecCallback.failed(ex);
                                }

                                @Override
                                public void cancelled() {
                                    asyncExecCallback.failed(new InterruptedIOException());
                                }

                            }));

                } else {
                    asyncExecCallback.completed();
                }
            }

            @Override
            public void failed(final Exception cause) {
                asyncExecCallback.failed(cause);
            }

        });

    }

}
