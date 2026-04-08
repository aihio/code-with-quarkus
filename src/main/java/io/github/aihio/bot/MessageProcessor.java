package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MessageProcessor extends RouteBuilder {

    @ConfigProperty(name = "telegram.token")
    String token;

    @Override
    public void configure() {
        from("telegram:bots?authorizationToken=" + token)
                .log("${body}")
                .process(exchange -> {
                    var incoming = exchange.getMessage().getBody(String.class);
                    var msg = new OutgoingTextMessage();
                    msg.setText(incoming);
                    exchange.getIn().setBody(msg);
                })
                .to("telegram:bots?authorizationToken=" + token);

    }
}
