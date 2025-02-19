/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ilm.action;

import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.immutablestate.ImmutableClusterStateHandler;
import org.elasticsearch.immutablestate.TransformState;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.action.PutLifecycleAction;
import org.elasticsearch.xpack.core.template.LifecyclePolicyConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.common.util.Maps.asMap;
import static org.elasticsearch.common.xcontent.XContentHelper.mapToXContentParser;

/**
 * This {@link org.elasticsearch.immutablestate.ImmutableClusterStateHandler} is responsible for immutable state
 * CRUD operations on ILM policies in, e.g. file based settings.
 * <p>
 * Internally it uses {@link TransportPutLifecycleAction} and
 * {@link TransportDeleteLifecycleAction} to add, update and delete ILM policies.
 */
public class ImmutableLifecycleAction implements ImmutableClusterStateHandler<LifecyclePolicy> {

    private final NamedXContentRegistry xContentRegistry;
    private final Client client;
    private final XPackLicenseState licenseState;

    public static final String NAME = "ilm";

    public ImmutableLifecycleAction(NamedXContentRegistry xContentRegistry, Client client, XPackLicenseState licenseState) {
        this.xContentRegistry = xContentRegistry;
        this.client = client;
        this.licenseState = licenseState;
    }

    @Override
    public String name() {
        return NAME;
    }

    @SuppressWarnings("unchecked")
    public Collection<PutLifecycleAction.Request> prepare(Object input) throws IOException {
        List<PutLifecycleAction.Request> result = new ArrayList<>();

        Map<String, ?> source = asMap(input);

        for (String name : source.keySet()) {
            Map<String, ?> content = (Map<String, ?>) source.get(name);
            var config = XContentParserConfiguration.EMPTY.withRegistry(LifecyclePolicyConfig.DEFAULT_X_CONTENT_REGISTRY);
            try (XContentParser parser = mapToXContentParser(config, content)) {
                LifecyclePolicy policy = LifecyclePolicy.parse(parser, name);
                PutLifecycleAction.Request request = new PutLifecycleAction.Request(policy);
                validate(request);
                result.add(request);
            }
        }

        return result;
    }

    @Override
    public TransformState transform(Object source, TransformState prevState) throws Exception {
        var requests = prepare(source);

        ClusterState state = prevState.state();

        for (var request : requests) {
            TransportPutLifecycleAction.UpdateLifecyclePolicyTask task = new TransportPutLifecycleAction.UpdateLifecyclePolicyTask(
                request,
                licenseState,
                xContentRegistry,
                client
            );

            state = task.execute(state);
        }

        Set<String> entities = requests.stream().map(r -> r.getPolicy().getName()).collect(Collectors.toSet());

        Set<String> toDelete = new HashSet<>(prevState.keys());
        toDelete.removeAll(entities);

        for (var policyToDelete : toDelete) {
            TransportDeleteLifecycleAction.DeleteLifecyclePolicyTask task = new TransportDeleteLifecycleAction.DeleteLifecyclePolicyTask(
                policyToDelete
            );
            state = task.execute(state);
        }

        return new TransformState(state, entities);
    }
}
