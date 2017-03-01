package com.example;

import com.netflix.discovery.converters.Auto;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.hateoas.Resources;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

@EnableBinding(ReservationChannels.class)  // This actives spring-cloud-stream binding
@EnableCircuitBreaker
@EnableZuulProxy // sets up proxied routes.
@SpringBootApplication
@EnableDiscoveryClient
public class ReservationClientApplication {

    @Bean
    /*
	* Qualifier annotation should have configured on it a interceptor that will do client side load balancing
	* for us
	* */
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    public static void main(String[] args) {
        SpringApplication.run(ReservationClientApplication.class, args);
    }
}



interface  ReservationChannels {
    @Output
    MessageChannel output();
}

@RestController
@RequestMapping("/reservations")
class ReservationApiGatewayRestController {

    @Autowired
    private RestTemplate restTemplate;

    private MessageChannel output;

    public ReservationApiGatewayRestController() {
    }

    @Autowired
    public ReservationApiGatewayRestController(ReservationChannels reservationChannels) {
        this.output = reservationChannels.output();
    }

    public Collection<String> fallback(){
        return Collections.singletonList("Downstream service is down");
    }


    @RequestMapping(method = RequestMethod.POST)
    public void write(@RequestBody Reservation reservation){
        String reservationName = reservation.getReservationName();
        Message<String> msg = MessageBuilder.withPayload(reservationName).build();
        this.output.send(msg);
    }

    @HystrixCommand(fallbackMethod = "fallback")
    @RequestMapping(method = RequestMethod.GET, value = "/names")
    public Collection<String> getNames() {


        ParameterizedTypeReference<Resources<Reservation>> ptr = new ParameterizedTypeReference<Resources<Reservation>>() {
        };

        //Now I can ask for its type

        //Type type = ptr.getType();

        ResponseEntity<Resources<Reservation>> responseEntity = restTemplate.exchange("http://reservation-service/reservations", HttpMethod.GET, null, ptr);


        return responseEntity.getBody()
                .getContent()
                .stream()
                .map(Reservation::getReservationName)
                .collect(Collectors.toList());
    }


}

/**
 * Create a Reservation DTO, so that we don't couple with actual Reservation object in actual service.
 * If we use google protocol buffers or some schema based encoding, then this becomes easier.
 */
class Reservation {
    private String reservationName;

    public String getReservationName() {
        return reservationName;
    }
}

@Component
class RateLimiterFilter extends ZuulFilter {

    private final com.google.common.util.concurrent.RateLimiter rateLimiter =
            com.google.common.util.concurrent.RateLimiter.create(1.0 / 30.0);

    @Override
    public String filterType() {
        return "pre"; // filter all requests before
    }

    @Override
    public int filterOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public boolean shouldFilter() {
        return true;
    }

    @Override
    public Object run() {

        RequestContext currentContext = RequestContext.getCurrentContext();

        HttpServletResponse response = currentContext.getResponse();

        if (!rateLimiter.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            currentContext.setSendZuulResponse(false);
            try {
                throw new ZuulException("Couldn't proceed ", HttpStatus.TOO_MANY_REQUESTS.value(), HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
            } catch (ZuulException e) {
                ReflectionUtils.rethrowRuntimeException(e);
            }
        }
        return null;
    }
}
