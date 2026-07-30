package org.misqzy.flectonegrowth.common.config.migration;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads/mutates a composed SnakeYAML {@link MappingNode} tree by top-level
 * key, in place of the plain {@code Map<String,Object>} API - used so
 * {@link ConfigMigrationStep}s and {@link ConfigMigrations#mergeMissing} can
 * rewrite a config file's tree without losing the comments {@code
 * Yaml.compose}/{@code Yaml.serialize} (with {@code setProcessComments(true)})
 * attach to each {@link Node}, unlike {@code Yaml.load}/{@code Yaml.dump}'s
 * {@code Map} view. Deliberately minimal - only what the two existing
 * migration steps and the merge pass need, not a general YAML editing API.
 *
 * <p>{@link ScalarNode} has no value setter (its value is constructor-only),
 * so "updating" an existing scalar means building a replacement node and
 * copying the old node's comments onto it before swapping it into the same
 * list position - see {@link #setOrAppend}.</p>
 */
public final class YamlNodeOps {

    private YamlNodeOps() {}

    public static Optional<NodeTuple> find(MappingNode map, String key) {
        for (NodeTuple tuple : map.getValue()) {
            if (key.equals(scalarValue(tuple.getKeyNode()))) return Optional.of(tuple);
        }
        return Optional.empty();
    }

    public static boolean has(MappingNode map, String key) {
        return find(map, key).isPresent();
    }

    public static void remove(MappingNode map, String key) {
        List<NodeTuple> tuples = new ArrayList<>(map.getValue());
        tuples.removeIf(t -> key.equals(scalarValue(t.getKeyNode())));
        map.setValue(tuples);
    }

    public static MappingNode getMapping(MappingNode map, String key) {
        return find(map, key)
                .map(NodeTuple::getValueNode)
                .filter(MappingNode.class::isInstance)
                .map(MappingNode.class::cast)
                .orElse(null);
    }

    public static SequenceNode getSequence(MappingNode map, String key) {
        return find(map, key)
                .map(NodeTuple::getValueNode)
                .filter(SequenceNode.class::isInstance)
                .map(SequenceNode.class::cast)
                .orElse(null);
    }

    public static String getScalarString(MappingNode map, String key) {
        return find(map, key)
                .map(NodeTuple::getValueNode)
                .filter(ScalarNode.class::isInstance)
                .map(n -> ((ScalarNode) n).getValue())
                .orElse(null);
    }

    /** Every scalar entry of a sequence node, as strings - e.g. a YAML list of server ids. {@code null}-safe. */
    public static List<String> scalarStrings(SequenceNode sequence) {
        List<String> out = new ArrayList<>();
        if (sequence == null) return out;
        for (Node node : sequence.getValue()) {
            if (node instanceof ScalarNode scalar) out.add(scalar.getValue());
        }
        return out;
    }

    /** Sets a plain string scalar, adding it if absent - see {@link #setOrAppend}. */
    public static void putScalarString(MappingNode map, String key, String value) {
        setOrAppend(map, key, Tag.STR, value);
    }

    /** Sets a boolean scalar, adding it if absent - see {@link #setOrAppend}. */
    public static void putScalarBoolean(MappingNode map, String key, boolean value) {
        setOrAppend(map, key, Tag.BOOL, String.valueOf(value));
    }

    /** Only sets the boolean scalar if {@code key} isn't already present - never overwrites an admin-set value. */
    public static void putScalarBooleanIfAbsent(MappingNode map, String key, boolean value) {
        if (!has(map, key)) putScalarBoolean(map, key, value);
    }

    private static void setOrAppend(MappingNode map, String key, Tag tag, String rawValue) {
        List<NodeTuple> tuples = new ArrayList<>(map.getValue());
        for (int i = 0; i < tuples.size(); i++) {
            NodeTuple existing = tuples.get(i);
            if (!key.equals(scalarValue(existing.getKeyNode()))) continue;

            Node oldValue = existing.getValueNode();
            ScalarNode newValue = scalar(tag, rawValue);
            newValue.setBlockComments(oldValue.getBlockComments());
            newValue.setInLineComments(oldValue.getInLineComments());
            newValue.setEndComments(oldValue.getEndComments());
            tuples.set(i, new NodeTuple(existing.getKeyNode(), newValue));
            map.setValue(tuples);
            return;
        }

        tuples.add(new NodeTuple(scalar(Tag.STR, key), scalar(tag, rawValue)));
        map.setValue(tuples);
    }

    private static ScalarNode scalar(Tag tag, String value) {
        return new ScalarNode(tag, value, null, null, DumperOptions.ScalarStyle.PLAIN);
    }

    private static String scalarValue(Node node) {
        return node instanceof ScalarNode scalar ? scalar.getValue() : null;
    }
}
