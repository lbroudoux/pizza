package com.salaboy.pizza.kitchen;

import io.dapr.testcontainers.Component;
import io.dapr.testcontainers.DaprContainer;
import io.dapr.testcontainers.DaprLogLevel;
import io.dapr.testcontainers.Subscription;
import io.github.microcks.testcontainers.MicrocksContainersEnsemble;
import io.github.microcks.testcontainers.connection.KafkaConnection;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
public class DaprTestContainersConfig {

    private KafkaContainer kafkaContainer;
    private DaprContainer daprContainer;

    @Bean
    public Network daprNetwork(Environment env) {
        boolean reuse = env.getProperty("reuse", Boolean.class, false);
        if (reuse) {
            Network defaultDaprNetwork = new Network() {
                @Override
                public String getId() {
                    return "dapr-network";
                }

                @Override
                public void close() {

                }

                @Override
                public Statement apply(Statement base, Description description) {
                    return null;
                }
            };

            List<com.github.dockerjava.api.model.Network> networks = DockerClientFactory.instance().client().listNetworksCmd()
                    .withNameFilter("dapr-network").exec();
            if (networks.isEmpty()) {
                Network.builder().createNetworkCmdModifier(cmd -> cmd.withName("dapr-network")).build().getId();
                return defaultDaprNetwork;
            } else {
                return defaultDaprNetwork;
            }
        } else {
            return Network.newNetwork();
        }
    }

    @Bean
    @ServiceConnection
    DaprContainer daprContainer(KafkaContainer kafkaContainer, Environment env, Network network) {
        boolean reuse = env.getProperty("reuse", Boolean.class, false);
        daprContainer = new DaprContainer("daprio/daprd:1.16.0-rc.5")
            .withAppName("kitchen-service")
            .withAppPort(8081)
            .withNetwork(network).withReusablePlacement(reuse)
            .withComponent(new Component("pubsub", "pubsub.kafka", "v1",
                  Map.of(
                        "brokers", "kafka:19092",
                        "authType", "none"
                  )))
            .withSubscription(new Subscription(
                  "pizza-store-subscription",
                  "pubsub", "topic", "/events"))
            .withAppChannelAddress("host.testcontainers.internal")
            .withDaprLogLevel(DaprLogLevel.DEBUG)
            .withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
            .dependsOn(kafkaContainer);

        org.testcontainers.Testcontainers.exposeHostPorts(8081);
        return daprContainer;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer(Environment env, Network network) {
        boolean reuse = env.getProperty("reuse", Boolean.class, false);
        kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withNetwork(network).withReuse(reuse)
            .withNetworkAliases("kafka")
            .withListener(() -> "kafka:19092");
        return kafkaContainer;
    }

    @Bean
    @ConditionalOnProperty(prefix = "tests", name = "mocks", havingValue = "true")
    MicrocksContainersEnsemble microcksEnsemble(Network network) {
        return new MicrocksContainersEnsemble(network, "quay.io/microcks/microcks-uber:1.11.0-native")
            .withAsyncFeature()
            .withAccessToHost(true)
            .withKafkaConnection(new KafkaConnection("kafka:19092"))
            .withMainArtifacts("kitchen-openapi.yaml", "kitchen-asyncapi.yaml")
            .withAsyncDependsOn(kafkaContainer);

    }
}
