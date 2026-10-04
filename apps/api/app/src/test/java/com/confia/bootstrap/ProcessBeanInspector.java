package com.confia.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;

/**
 * Computes where each bean of a started context comes from and compares it with a {@link
 * ProcessBeanPolicy} (design.md decision 4 of process-entry-point-isolation).
 *
 * <p>A bean has up to three origin packages: its user class (a CGLIB subclass is stripped), the
 * class named by its merged bean definition, and the user class of its factory bean, which is
 * where a {@code @Bean} method of an external type was declared. A bean is "of CONFIA" when any
 * origin package is under {@code com.confia}.
 */
final class ProcessBeanInspector {

    private static final String CONFIA_ROOT = "com.confia";

    record InspectedBean(String name, Set<String> originPackages) {
    }

    private ProcessBeanInspector() {
    }

    /** Beans with at least one origin package under {@code com.confia}. */
    static List<InspectedBean> confiaBeans(ConfigurableApplicationContext context) {
        return allBeans(context).stream()
                .filter(bean -> bean.originPackages().stream()
                        .anyMatch(pkg -> ProcessBeanPolicy.matches(pkg, CONFIA_ROOT)))
                .toList();
    }

    /** One readable line per violation: process, bean, package and rule; empty when clean. */
    static List<String> violations(ConfigurableApplicationContext context,
            ProcessBeanPolicy policy) {
        List<String> violations = new ArrayList<>();
        for (InspectedBean bean : allBeans(context)) {
            for (String pkg : bean.originPackages()) {
                policy.forbiddenPackages().forEach((forbidden, reason) -> {
                    if (ProcessBeanPolicy.matches(pkg, forbidden)) {
                        violations.add(describe(policy, bean, pkg, "forbidden: " + reason));
                    }
                });
                boolean confia = ProcessBeanPolicy.matches(pkg, CONFIA_ROOT);
                boolean allowed = policy.allowedPackages().stream()
                        .anyMatch(candidate -> ProcessBeanPolicy.matches(pkg, candidate));
                if (confia && !allowed) {
                    violations.add(describe(policy, bean, pkg, "not in the allow-list"));
                }
            }
        }
        return violations;
    }

    /** Beans whose user class carries {@code @SpringBootConfiguration}, directly or as meta. */
    static List<String> entryPointConfigurations(ConfigurableApplicationContext context) {
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        List<String> names = new ArrayList<>();
        factory.getBeanNamesIterator().forEachRemaining(name -> {
            Class<?> type = factory.getType(name, false);
            if (type != null && MergedAnnotations.from(ClassUtils.getUserClass(type))
                    .isPresent(SpringBootConfiguration.class)) {
                names.add(name);
            }
        });
        return names;
    }

    private static String describe(ProcessBeanPolicy policy, InspectedBean bean, String pkg,
            String rule) {
        return "process '%s': bean '%s' from package '%s' - %s"
                .formatted(policy.process(), bean.name(), pkg, rule);
    }

    private static List<InspectedBean> allBeans(ConfigurableApplicationContext context) {
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        List<InspectedBean> beans = new ArrayList<>();
        factory.getBeanNamesIterator().forEachRemaining(name -> {
            Set<String> origins = new TreeSet<>();
            addPackageOf(origins, factory.getType(name, false));
            if (factory.containsBeanDefinition(name)) {
                BeanDefinition definition = factory.getMergedBeanDefinition(name);
                if (definition.getBeanClassName() != null) {
                    origins.add(ClassUtils.getPackageName(definition.getBeanClassName()));
                }
                String factoryBeanName = definition.getFactoryBeanName();
                if (factoryBeanName != null) {
                    addPackageOf(origins, factory.getType(factoryBeanName, false));
                }
            }
            origins.remove("");
            beans.add(new InspectedBean(name, origins));
        });
        return beans;
    }

    private static void addPackageOf(Set<String> origins, Class<?> type) {
        if (type != null) {
            origins.add(ClassUtils.getPackageName(ClassUtils.getUserClass(type)));
        }
    }
}
