package com.pulsar.loadbalancer;

import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Optional;

public interface LoadBalancer {
    Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes);
}