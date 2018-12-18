package router;

import akka.stream.Materializer;
import controllers.*;
import play.api.mvc.Handler;
import play.api.mvc.RequestHeader;
import play.mvc.Http;
import play.api.routing.Router;
import play.routing.RoutingDsl;
import scala.Option;
import scala.PartialFunction;
import scala.Tuple3;
import scala.collection.Seq;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.webjars.play.RequireJS;
import org.webjars.play.WebJarAssets;

/**
 * We use the Java DSL router instead of the Play routes file, so that this project can also be built by Maven, which
 * as yet does not have a plugin for compiling Play routers.
 */
@Singleton
public class Routes implements Router {

    private final Application application;
    private final Assets assets;
    private final WebJarAssets webJars;
    private final RequireJS requireJs;
    private final Materializer materializer;

    private final RoutingDsl routingDsl;
    private final Router router;

    @Inject
    public Routes(Application application,
                  Assets assets,
                  WebJarAssets webJars,
                  RequireJS requireJs,
                  Materializer materializer,
                  RoutingDsl routingDsl) {
        this.application = application;
        this.assets = assets;
        this.webJars = webJars;
        this.requireJs = requireJs;
        this.materializer = materializer;

        this.routingDsl = routingDsl;
        this.router = buildRouter();
    }

    private Router buildRouter() {
        return this.routingDsl
                // Index
                .GET("/").routingTo((_req) -> application.index())
                .GET("/signup").routingTo((_req) -> application.index())
                .GET("/addFriend").routingTo((_req) -> application.index())
                .GET("/users/:id").<String>routingTo((_req, userId) -> application.userStream(userId))
                .GET("/cb").routingTo((_req) -> application.circuitBreaker())

                // Assets
                .GET("/webjars/_requirejs").routingAsync((req) ->
                        requireJs.setup()
                                .asJava()
                                .apply(req)
                                .run(materializer)
                )
                .GET("/assets/*file").<String>routingAsync((req, file) ->
                        assets.at("/public", file, false)
                                .asJava()
                                .apply(req)
                                .run(materializer)
                )
                .GET("/webjars/*file").<String>routingAsync((req, file) ->
                        webJars.at(file)
                                .asJava()
                                .apply(req)
                                .run(materializer)
                )
                .build().asScala();
    }

    @Override
    public PartialFunction<RequestHeader, Handler> routes() {
        return router.routes();
    }

    @Override
    public Seq<Tuple3<String, String, String>> documentation() {
        return router.documentation();
    }

    @Override
    public Router withPrefix(String prefix) {
        return router.withPrefix(prefix);
    }

    @Override
    public Option<Handler> handlerFor(RequestHeader request) {
        return router.handlerFor(request);
    }

    @Override
    public play.routing.Router asJava() {
        return router.asJava();
    }
}
