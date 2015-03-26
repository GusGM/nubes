package mock.controllers;

import io.vertx.ext.apex.RoutingContext;
import io.vertx.mvc.annotations.Controller;
import io.vertx.mvc.annotations.Path;
import io.vertx.mvc.annotations.methods.GET;
import io.vertx.mvc.controllers.AbstractController;

@Controller
public class HelloController extends AbstractController {

    @Path("/hello")
    @GET
    public void sayHello(RoutingContext context) {
        renderText(context, "Hello world!");
    }

}
