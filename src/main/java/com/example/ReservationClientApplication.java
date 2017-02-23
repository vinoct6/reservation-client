package com.example;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import javax.servlet.http.HttpServletResponse;

@EnableZuulProxy // sets up proxied routes.
@SpringBootApplication
@EnableDiscoveryClient
public class ReservationClientApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReservationClientApplication.class, args);
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

		if(!rateLimiter.tryAcquire()){
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
