package io.github.aihio.bot;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;

public class MessageProcessor extends RouteBuilder {
    @Override
    public void configure() {
        from("telegram:bots")
                .log("${body}")
                .process(exchange -> {
                    var incoming = exchange.getMessage().getBody(String.class);
                    var msg = new OutgoingTextMessage();
                    msg.setText(incoming);
                    exchange.getIn().setBody(msg);
                })
                .to("telegram:bots");
    }
}
