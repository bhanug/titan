package com.thinkaurelius.titan.olap;

import org.apache.tinkerpop.gremlin.process.Traversal;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.MessageCombiner;
import org.apache.tinkerpop.gremlin.process.computer.MessageScope;
import org.apache.tinkerpop.gremlin.process.computer.Messenger;
import org.apache.tinkerpop.gremlin.process.computer.util.AbstractVertexProgramBuilder;
import org.apache.tinkerpop.gremlin.process.computer.util.LambdaHolder;
import org.apache.tinkerpop.gremlin.process.computer.util.StaticVertexProgram;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.StreamFactory;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.tinkerpop.gremlin.process.graph.AnonymousGraphTraversal.Tokens.__;

public class ShortestDistanceVertexProgram extends StaticVertexProgram<Long> {

    private static final Logger log =
            LoggerFactory.getLogger(ShortestDistanceVertexProgram.class);

    private MessageScope.Local<Long> incidentMessageScope;

    public static final String DISTANCE = "titan.shortestDistanceVertexProgram.distance";
    public static final String MAX_DEPTH = "titan.shortestDistanceVertexProgram.maxDepth";
    public static final String WEIGHT_PROPERTY = "titan.shortestDistanceVertexProgram.weightProperty";
    public static final String SEED = "titan.shortestDistanceVertexProgram.seedID";

    private static final String INCIDENT_TRAVERSAL_SUPPLIER = "titan.shortestDistanceVertexProgram.incidentTraversalSupplier";
    private LambdaHolder<Supplier<Traversal<Vertex, Edge>>> traversalSupplier;

    private int maxDepth;
    private long seed;
    private String weightProperty;

    private static final Set<String> COMPUTE_KEYS = new HashSet<>(Arrays.asList(DISTANCE));

    private ShortestDistanceVertexProgram() {

    }

    @Override
    public void loadState(final Configuration configuration) {
//        this.traversalSupplier = LambdaHolder.loadState(configuration, INCIDENT_TRAVERSAL_SUPPLIER);
//        if (null != traversalSupplier) {
//            //VertexProgramHelper.verifyReversibility(this.traversalSupplier.get().get().asAdmin());
//            incidentMessageScope = MessageScope.Local.of(traversalSupplier.get());
//        }
        maxDepth = configuration.getInt(MAX_DEPTH);
        seed = configuration.getLong(SEED);
        weightProperty = configuration.getString(WEIGHT_PROPERTY, "distance");
        incidentMessageScope = MessageScope.Local.of(__::inE, (msg, edge) -> msg + edge.<Integer>value(weightProperty));
        log.debug("Loaded maxDepth={}", maxDepth);
    }

    @Override
    public void storeState(final Configuration configuration) {
        configuration.setProperty(VERTEX_PROGRAM, ShortestDistanceVertexProgram.class.getName());
        configuration.setProperty(MAX_DEPTH, maxDepth);
//        if (null != traversalSupplier) {
//            traversalSupplier.storeState(configuration);
//        }
    }

    @Override
    public Set<String> getElementComputeKeys() {
        return COMPUTE_KEYS;
    }

    @Override
    public Optional<MessageCombiner<Long>> getMessageCombiner() {
        return (Optional) ShortestDistanceMessageCombiner.instance();
    }

    @Override
    public Set<MessageScope> getMessageScopes(final Memory memory) {
        final Set<MessageScope> set = new HashSet<>();
        set.add(incidentMessageScope);
        return set;
    }

    @Override
    public void setup(final Memory memory) {

    }

    @Override
    public void execute(final Vertex vertex, Messenger<Long> messenger, final Memory memory) {
        if (memory.isInitialIteration()) {
            if (vertex.id().equals(Long.valueOf(seed).longValue())) {
                // The seed sends a single message to start the computation
                log.debug("Sent initial message from {}", vertex.id());
                // The seed's distance to itself is zero
                vertex.singleProperty(DISTANCE, 0L);
                messenger.sendMessage(incidentMessageScope, 0L);
            }
        } else {
            Iterable<Long> distances = messenger.receiveMessages(incidentMessageScope);

            // Find minimum distance among all incoming messages, or null if no messages came in
            Long shortestDistanceSeenOnThisIteration =
                    StreamFactory.stream(distances).reduce((a,b) -> Math.min(a,b)).orElse(null);

            if (null == shortestDistanceSeenOnThisIteration)
                return; // no messages to process or forward on this superstep

            VertexProperty<Long> currentShortestVP = vertex.property(DISTANCE);

            if (!currentShortestVP.isPresent() ||
                    currentShortestVP.value() > shortestDistanceSeenOnThisIteration) {
                // First/shortest distance seen by this vertex: store it and forward to neighbors
                vertex.singleProperty(DISTANCE, shortestDistanceSeenOnThisIteration);
                messenger.sendMessage(incidentMessageScope, shortestDistanceSeenOnThisIteration);
            }
            // else: no new winner, ergo no reason to send message to neighbors
        }
    }

    @Override
    public boolean terminate(final Memory memory) {
        return memory.getIteration() >= this.maxDepth;
    }

    @Override
    public String toString() {
        return StringFactory.vertexProgramString(this, "maxDepth=" + maxDepth);
    }

    //////////////////////////////

    public static Builder build() {
        return new Builder();
    }

    public static class Builder extends AbstractVertexProgramBuilder<Builder> {

        private Builder() {
            super(ShortestDistanceVertexProgram.class);
        }

        public Builder maxDepth(final int maxDepth) {
            this.configuration.setProperty(MAX_DEPTH, maxDepth);
            return this;
        }

        public Builder seed(final long seed) {
            this.configuration.setProperty(SEED, seed);
            return this;
        }
    }

    ////////////////////////////

    @Override
    public Features getFeatures() {
        return new Features() {
            @Override
            public boolean requiresLocalMessageScopes() {
                return true;
            }

            @Override
            public boolean requiresVertexPropertyAddition() {
                return true;
            }
        };
    }
}