package generator

import (
	"fmt"
	"math"
	"sort"
	"strconv"

	"gopkg.in/yaml.v3"
)

// mapBuilder 保证键顺序稳定的 YAML 映射构造器。
type mapBuilder struct {
	content []*yaml.Node
}

func newMap() *mapBuilder { return &mapBuilder{} }

func (m *mapBuilder) add(key string, value any) *mapBuilder {
	return m.addNode(key, toNode(value))
}

func (m *mapBuilder) addNode(key string, node *yaml.Node) *mapBuilder {
	m.content = append(m.content,
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key},
		node,
	)
	return m
}

func (m *mapBuilder) node() *yaml.Node {
	return &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map", Content: m.content}
}

func seq(items ...*yaml.Node) *yaml.Node {
	return &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq", Content: items}
}

func strSeq(values []string) *yaml.Node {
	items := make([]*yaml.Node, 0, len(values))
	for _, v := range values {
		items = append(items, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: v})
	}
	return seq(items...)
}

// toNode 把任意 Go 值转换为 YAML 节点。
func toNode(v any) *yaml.Node {
	switch t := v.(type) {
	case nil:
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!null", Value: "null"}
	case *yaml.Node:
		return t
	case yaml.Node:
		return &t
	case string:
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: t}
	case bool:
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!bool", Value: strconv.FormatBool(t)}
	case int:
		return intNode(int64(t))
	case int8:
		return intNode(int64(t))
	case int16:
		return intNode(int64(t))
	case int32:
		return intNode(int64(t))
	case int64:
		return intNode(t)
	case uint:
		return intNode(int64(t))
	case uint8:
		return intNode(int64(t))
	case uint16:
		return intNode(int64(t))
	case uint32:
		return intNode(int64(t))
	case uint64:
		return intNode(int64(t))
	case float32:
		return floatNode(float64(t))
	case float64:
		return floatNode(t)
	case []string:
		return strSeq(t)
	case []any:
		items := make([]*yaml.Node, 0, len(t))
		for _, item := range t {
			items = append(items, toNode(item))
		}
		return seq(items...)
	case map[string]any:
		m := newMap()
		for _, key := range sortedKeys(t) {
			m.add(key, t[key])
		}
		return m.node()
	case map[string]string:
		m := newMap()
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			m.add(k, t[k])
		}
		return m.node()
	default:
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: fmt.Sprintf("%v", t)}
	}
}

func intNode(v int64) *yaml.Node {
	return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!int", Value: strconv.FormatInt(v, 10)}
}

func floatNode(v float64) *yaml.Node {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: strconv.FormatFloat(v, 'g', -1, 64)}
	}
	return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!float", Value: strconv.FormatFloat(v, 'g', -1, 64)}
}
