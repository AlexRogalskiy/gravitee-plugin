/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.plugin.core.internal;

import io.gravitee.plugin.core.api.Plugin;
import io.gravitee.plugin.core.api.PluginConfigurationResolver;
import io.gravitee.plugin.core.api.PluginContextFactory;
import io.gravitee.plugin.core.api.PluginType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class PluginContextFactoryImpl implements PluginContextFactory, ApplicationContextAware {

    protected final Logger LOGGER = LoggerFactory.getLogger(PluginContextFactoryImpl.class);

    private final Map<Plugin, ApplicationContext> contexts = new HashMap<>();

    private ApplicationContext applicationContext;

    @Autowired
    private PluginConfigurationResolver defaultPluginConfigurationResolver;

    @Override
    public ApplicationContext create(PluginConfigurationResolver configurationResolver, Plugin plugin) {
        LOGGER.debug("Create Spring context for plugin: {}", plugin.id());

        Set<Class<?>> configurations = configurationResolver.resolve(plugin);

        AnnotationConfigApplicationContext pluginContext = new AnnotationConfigApplicationContext();
        pluginContext.setClassLoader(plugin.clazz().getClassLoader());
        pluginContext.setEnvironment((ConfigurableEnvironment) applicationContext.getEnvironment());

        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setIgnoreUnresolvablePlaceholders(true);
        configurer.setEnvironment(applicationContext.getEnvironment());
        pluginContext.addBeanFactoryPostProcessor(configurer);

        // Copy bean from parent context to plugin context
//        EventManager eventManager = applicationContext.getBean(EventManager.class);
        pluginContext.setParent(applicationContext);

        if (configurations.isEmpty()) {
            LOGGER.info("\tNo @Configuration annotated class found for plugin {}", plugin.id());
        } else {
            LOGGER.debug("\t{} Spring @Configuration annotated class found for plugin {}", configurations.size(), plugin.id());
            configurations.forEach(pluginContext::register);
        }

        // Only reporters and services can be inject by Spring
        if (plugin.type() != PluginType.POLICY) {
            BeanDefinition beanDefinition =
                    BeanDefinitionBuilder.rootBeanDefinition(plugin.clazz().getName()).getBeanDefinition();

            LOGGER.debug("\tRegistering a new bean definition for class: {}", plugin.clazz().getName());
            pluginContext.registerBeanDefinition(plugin.clazz().getName(), beanDefinition);
        }

        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(plugin.clazz().getClassLoader());
            pluginContext.refresh();
        } catch (Exception ex) {
            LOGGER.error("Unable to refresh plugin Spring context", ex);
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }

        contexts.putIfAbsent(plugin, pluginContext);

        return pluginContext;
    }

    @Override
    public ApplicationContext create(Plugin plugin) {
        return create(defaultPluginConfigurationResolver, plugin);
    }

    @Override
    public void remove(Plugin plugin) {
        ApplicationContext ctx =  contexts.remove(plugin);
        if (ctx != null) {
            ((ConfigurableApplicationContext)ctx).close();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
