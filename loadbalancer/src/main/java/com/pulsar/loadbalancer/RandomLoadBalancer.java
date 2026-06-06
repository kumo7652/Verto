package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@SpiExtension(name = "random")
public class RandomLoadBalancer extends AbstractLoadBalancer {

    @Override
    protected Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes) {
        int index = ThreadLocalRandom.current().nextInt(nodes.size());
        return Optional.of(nodes.get(index));
    }
}
