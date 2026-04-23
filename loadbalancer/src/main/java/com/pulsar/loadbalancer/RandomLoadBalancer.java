package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@SpiExtension(name = "random")
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        if (nodes.size() == 1) {
            return Optional.of(nodes.get(0));
        }

        return Optional.of(nodes.get(
                ThreadLocalRandom.current().nextInt(nodes.size())));
    }
}
