package io.vertx.nubes;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.apex.Router;
import io.vertx.ext.apex.RoutingContext;
import io.vertx.ext.apex.handler.BodyHandler;
import io.vertx.ext.apex.handler.CookieHandler;
import io.vertx.ext.apex.handler.StaticHandler;
import io.vertx.nubes.annotations.View;
import io.vertx.nubes.annotations.cookies.CookieValue;
import io.vertx.nubes.annotations.cookies.Cookies;
import io.vertx.nubes.annotations.mixins.ContentType;
import io.vertx.nubes.annotations.mixins.Throttled;
import io.vertx.nubes.annotations.routing.ClientRedirect;
import io.vertx.nubes.annotations.routing.POST;
import io.vertx.nubes.annotations.routing.PUT;
import io.vertx.nubes.context.ClientAccesses;
import io.vertx.nubes.context.PaginationContext;
import io.vertx.nubes.context.RateLimit;
import io.vertx.nubes.exceptions.MissingConfigurationException;
import io.vertx.nubes.fixtures.FixtureLoader;
import io.vertx.nubes.handlers.AnnotationProcessor;
import io.vertx.nubes.handlers.AnnotationProcessorRegistry;
import io.vertx.nubes.handlers.Processor;
import io.vertx.nubes.handlers.impl.ClientRedirectProcessor;
import io.vertx.nubes.handlers.impl.ContentTypeProcessor;
import io.vertx.nubes.handlers.impl.DefaultErrorHandler;
import io.vertx.nubes.handlers.impl.PaginationProcessor;
import io.vertx.nubes.handlers.impl.PayloadTypeProcessor;
import io.vertx.nubes.handlers.impl.RateLimitationHandler;
import io.vertx.nubes.handlers.impl.ViewProcessor;
import io.vertx.nubes.marshallers.Payload;
import io.vertx.nubes.marshallers.PayloadMarshaller;
import io.vertx.nubes.marshallers.impl.BoonPayloadMarshaller;
import io.vertx.nubes.reflections.RouteDiscovery;
import io.vertx.nubes.reflections.adapters.ParameterAdapter;
import io.vertx.nubes.reflections.adapters.ParameterAdapterRegistry;
import io.vertx.nubes.reflections.adapters.impl.DefaultParameterAdapter;
import io.vertx.nubes.reflections.injectors.annot.AnnotatedParamInjector;
import io.vertx.nubes.reflections.injectors.annot.AnnotatedParamInjectorRegistry;
import io.vertx.nubes.reflections.injectors.typed.ParamInjector;
import io.vertx.nubes.reflections.injectors.typed.TypedParamInjectorRegistry;
import io.vertx.nubes.services.Service;
import io.vertx.nubes.services.ServiceRegistry;
import io.vertx.nubes.utils.MultipleFutures;
import io.vertx.nubes.views.TemplateEngineManager;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VertxNubes {

    private Config config;
    private Vertx vertx;
    private Router router;
    private FixtureLoader fixtureLoader;
    private ParameterAdapterRegistry registry;
    private AnnotationProcessorRegistry apRegistry;
    private TypedParamInjectorRegistry typeInjectors;
    private AnnotatedParamInjectorRegistry annotInjectors;
    private Map<Class<? extends Annotation>, Set<Handler<RoutingContext>>> annotationHandlers;
    private Map<Class<?>, Processor> typeProcessors;
    private Map<String, PayloadMarshaller> marshallers;
    private ServiceRegistry serviceRegistry;
    private Handler<RoutingContext> failureHandler;
    private TemplateEngineManager templManager;

    /**
     * TODO check config
     * 
     * @param vertx
     */
    public VertxNubes(Vertx vertx, JsonObject json) throws MissingConfigurationException {
        this.vertx = vertx;
        config = Config.fromJsonObject(json);
        registry = new ParameterAdapterRegistry(new DefaultParameterAdapter());
        annotationHandlers = new HashMap<Class<? extends Annotation>, Set<Handler<RoutingContext>>>();
        typeProcessors = new HashMap<Class<?>, Processor>();
        apRegistry = new AnnotationProcessorRegistry();
        marshallers = new HashMap<String, PayloadMarshaller>();
        typeInjectors = new TypedParamInjectorRegistry();
        annotInjectors = new AnnotatedParamInjectorRegistry(marshallers, registry);
        serviceRegistry = new ServiceRegistry(vertx);
        templManager = new TemplateEngineManager(config);
        CookieHandler cookieHandler = CookieHandler.create();
        BodyHandler bodyHandler = BodyHandler.create();
        registerAnnotationHandler(Cookies.class, cookieHandler);
        registerAnnotationHandler(CookieValue.class, cookieHandler);
        registerAnnotationHandler(Throttled.class, RateLimitationHandler.create(config));
        registerAnnotationHandler(POST.class, bodyHandler);
        registerAnnotationHandler(PUT.class, bodyHandler);
        registerTypeProcessor(PaginationContext.class, new PaginationProcessor());
        registerTypeProcessor(Payload.class, new PayloadTypeProcessor(marshallers));
        registerAnnotationProcessor(ClientRedirect.class, new ClientRedirectProcessor());
        registerAnnotationProcessor(ContentType.class, new ContentTypeProcessor());
        registerAnnotationProcessor(View.class, new ViewProcessor(templManager));
        registerMarshaller("application/json", new BoonPayloadMarshaller());
        failureHandler = new DefaultErrorHandler(config, templManager, marshallers);
    }

    public void bootstrap(Future<Router> future, Router paramRouter) {
        router = paramRouter;
        router.route().failureHandler(failureHandler);
        RouteDiscovery routeDiscovery = new RouteDiscovery(router, config, annotationHandlers, typeProcessors, apRegistry, typeInjectors, annotInjectors, serviceRegistry);
        routeDiscovery.createRoutes();
        StaticHandler staticHandler;
        if (config.webroot != null) {
            staticHandler = StaticHandler.create(config.webroot);
        } else {
            staticHandler = StaticHandler.create();
        }
        router.route(config.assetsPath + "/*").handler(staticHandler);

        // fixtures
        fixtureLoader = new FixtureLoader(vertx, config, serviceRegistry);
        Future<Void> fixturesFuture = Future.future();
        // services
        Future<Void> servicesFuture = Future.future();

        fixturesFuture.setHandler(result -> {
            if (result.succeeded()) {
                periodicallyCleanHistoryMap();
                future.complete(router);
            } else {
                future.fail(result.cause());
            }
        });

        servicesFuture.setHandler(result -> {
            if (result.succeeded()) {
                fixtureLoader.setUp(fixturesFuture);
            } else {
                future.fail(result.cause());
            }
        });

        serviceRegistry.startAll(servicesFuture);
    }

    public void bootstrap(Future<Router> future) {
        bootstrap(future, Router.router(vertx));
    }

    public void stop(Future<Void> future) {
        router.clear();
        MultipleFutures<Void> futures = new MultipleFutures<Void>();
        Future<Void> fixturesFuture = Future.future();
        Future<Void> servicesFuture = Future.future();
        futures.addFuture(fixturesFuture);
        futures.addFuture(servicesFuture);
        futures.setHandler(res -> {
            if (res.succeeded()) {
                future.complete();
            } else {
                future.fail(res.cause());
            }
        });
        serviceRegistry.stopAll(servicesFuture);
        fixtureLoader.tearDown(fixturesFuture);
    }

    public void setFailureHandler(Handler<RoutingContext> handler) {
        failureHandler = handler;
    }

    public void registerService(Service service) {
        serviceRegistry.registerService(service);
    }

    public <T> void registerAdapter(Class<T> parameterClass, ParameterAdapter<T> adapter) {
        registry.registerAdapter(parameterClass, adapter);
    }

    public void registerAnnotationHandler(Class<? extends Annotation> annotation, Handler<RoutingContext> handler) {
        Set<Handler<RoutingContext>> handlers = annotationHandlers.get(annotation);
        if (handlers == null) {
            handlers = new LinkedHashSet<Handler<RoutingContext>>();
        }
        handlers.add(handler);
        annotationHandlers.put(annotation, handlers);
    }

    public void registerTypeProcessor(Class<?> type, Processor processor) {
        typeProcessors.put(type, processor);
    }

    public <T extends Annotation> void registerAnnotationProcessor(Class<T> annotation, AnnotationProcessor<T> processor) {
        apRegistry.registerProcessor(annotation, processor);
    }

    public void registerMarshaller(String contentType, PayloadMarshaller marshaller) {
        marshallers.put(contentType, marshaller);
    }

    public <T> void registerTypeParamInjector(Class<? extends T> clazz, ParamInjector<T> injector) {
        typeInjectors.registerInjector(clazz, injector);
    }

    public <T extends Annotation> void registerAnnotatedParamInjector(Class<? extends T> clazz, AnnotatedParamInjector<T> injector) {
        annotInjectors.registerInjector(clazz, injector);
    }

    private void periodicallyCleanHistoryMap() {
        vertx.setPeriodic(60000, timerId -> {
            LocalMap<Object, Object> rateLimitations = vertx.sharedData().getLocalMap("mvc.rateLimitation");
            if (rateLimitations == null) {
                return;
            }
            List<String> clientIpsToRemove = new ArrayList<String>();
            RateLimit rateLimit = config.rateLimit;
            for (Object key : rateLimitations.keySet()) {
                String clientIp = (String) key;
                ClientAccesses accesses = (ClientAccesses) rateLimitations.get(clientIp);
                long keepAfter = config.rateLimit.getTimeUnit().toMillis(rateLimit.getValue());
                accesses.clearHistory(keepAfter);
                if (accesses.noAccess()) {
                    clientIpsToRemove.add(clientIp);
                }
            }
            clientIpsToRemove.forEach(clientIp -> {
                rateLimitations.remove(clientIp);
            });
        });
    }
}