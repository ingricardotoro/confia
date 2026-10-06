package com.confia.shared.web.ratelimit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Stops the start of the process when a handler method names a policy no limiter holds
 * (web-edge-foundations design.md, decision 17). It runs once every singleton exists, which is
 * before the context finishes refreshing and so before the web server accepts a connection: a typo
 * in {@code @RateLimited(policy = ...)} is a failed start naming the policy, never a route that is
 * silently unlimited or one that refuses everything at run time.
 */
public final class RateLimitPolicyCheck implements SmartInitializingSingleton {

    private final RateLimiterRegistry limiters;
    private final ListableBeanFactory beans;

    public RateLimitPolicyCheck(RateLimiterRegistry limiters, ListableBeanFactory beans) {
        this.limiters = limiters;
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<HandlerMethod> handlers = new ArrayList<>();
        beans.getBeansOfType(RequestMappingHandlerMapping.class).values()
                .forEach(mapping -> handlers.addAll(mapping.getHandlerMethods().values()));
        List<String> problems = unknownPolicies(handlers, limiters);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    /**
     * One sentence per handler method whose {@link RateLimited} policy is not registered, naming
     * the policy and the method; empty when every policy is known.
     */
    static List<String> unknownPolicies(Collection<HandlerMethod> handlers,
            RateLimiterRegistry limiters) {
        List<String> problems = new ArrayList<>();
        for (HandlerMethod handler : handlers) {
            RateLimited limited = handler.getMethodAnnotation(RateLimited.class);
            if (limited != null && limiters.find(limited.policy()).isEmpty()) {
                problems.add("@RateLimited names the unknown policy '" + limited.policy() + "' on "
                        + handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()
                        + " (known: " + limiters.policies() + ")");
            }
        }
        return problems;
    }
}
