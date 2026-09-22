package generator

import (
	"fmt"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// emit 把 YAML 节点树渲染成可读的 YAML 文本。
//
// 之所以不直接用 yaml.Marshal：yaml.v3 的 emitter 会把 4 字节 UTF-8 字符
// （国旗、火箭等 emoji）转义为 "\U0001F1ED"，虽然符合规范但可读性极差。
// 内置配置的结构完全由本程序构造，因此可以用一个受控的渲染器输出更友好的文本。
func emit(root *yaml.Node) ([]byte, error) {
	if root == nil {
		return nil, fmt.Errorf("没有可输出的内容")
	}
	if root.Kind == yaml.DocumentNode {
		if len(root.Content) == 0 {
			return nil, fmt.Errorf("没有可输出的内容")
		}
		root = root.Content[0]
	}
	var b strings.Builder
	switch root.Kind {
	case yaml.MappingNode:
		writeIndent(&b, 0)
		emitMapping(&b, root, 0)
	case yaml.SequenceNode:
		emitSequence(&b, root, 0)
	default:
		b.WriteString(scalarText(root))
		b.WriteString("\n")
	}
	return []byte(b.String()), nil
}

func writeIndent(b *strings.Builder, level int) {
	b.WriteString(strings.Repeat("  ", level))
}

func emitMapping(b *strings.Builder, node *yaml.Node, level int) {
	for i := 0; i+1 < len(node.Content); i += 2 {
		writeIndent(b, level)
		b.WriteString(scalarText(node.Content[i]))
		b.WriteString(":")
		emitValue(b, node.Content[i+1], level+1)
	}
}

// emitMappingInline 渲染 "- " 之后的映射，首个键与短横线同行。
func emitMappingInline(b *strings.Builder, node *yaml.Node, level int) {
	for i := 0; i+1 < len(node.Content); i += 2 {
		if i > 0 {
			writeIndent(b, level)
		}
		b.WriteString(scalarText(node.Content[i]))
		b.WriteString(":")
		emitValue(b, node.Content[i+1], level+1)
	}
}

func emitSequence(b *strings.Builder, node *yaml.Node, level int) {
	for _, item := range node.Content {
		writeIndent(b, level)
		b.WriteString("- ")
		switch item.Kind {
		case yaml.MappingNode:
			if len(item.Content) == 0 {
				b.WriteString("{}\n")
				continue
			}
			emitMappingInline(b, item, level+1)
		case yaml.SequenceNode:
			if len(item.Content) == 0 {
				b.WriteString("[]\n")
				continue
			}
			b.WriteString("\n")
			emitSequence(b, item, level+1)
		default:
			b.WriteString(scalarText(item))
			b.WriteString("\n")
		}
	}
}

// emitValue 渲染 "key:" 之后的值。
func emitValue(b *strings.Builder, node *yaml.Node, level int) {
	switch node.Kind {
	case yaml.MappingNode:
		if len(node.Content) == 0 {
			b.WriteString(" {}\n")
			return
		}
		b.WriteString("\n")
		emitMapping(b, node, level)
	case yaml.SequenceNode:
		if len(node.Content) == 0 {
			b.WriteString(" []\n")
			return
		}
		b.WriteString("\n")
		emitSequence(b, node, level)
	case yaml.AliasNode:
		b.WriteString(" *")
		b.WriteString(node.Value)
		b.WriteString("\n")
	default:
		b.WriteString(" ")
		b.WriteString(scalarText(node))
		b.WriteString("\n")
	}
}

// scalarText 输出标量，必要时加引号。
func scalarText(node *yaml.Node) string {
	if node == nil {
		return "null"
	}
	switch node.Kind {
	case yaml.MappingNode:
		return "{}"
	case yaml.SequenceNode:
		return "[]"
	case yaml.AliasNode:
		return "*" + node.Value
	}
	value := node.Value
	switch node.Tag {
	case "!!null":
		if value == "" {
			return "null"
		}
	case "!!int", "!!float", "!!bool":
		return value
	}
	if isPlainSafe(value) {
		return value
	}
	return quoteString(value)
}

func isPlainSafe(s string) bool {
	if s == "" {
		return false
	}
	if strings.TrimSpace(s) != s {
		return false
	}
	if strings.ContainsAny(s, "\n\r\t") {
		return false
	}
	if strings.Contains(s, ": ") || strings.HasSuffix(s, ":") {
		return false
	}
	if strings.Contains(s, " #") {
		return false
	}
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	switch s[0] {
	case '-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`':
		return false
	}
	return !looksLikeOtherType(s)
}

// looksLikeOtherType 判断字符串是否会被 YAML 解析成其它类型（数字、布尔、null）。
func looksLikeOtherType(s string) bool {
	switch strings.ToLower(s) {
	case "true", "false", "yes", "no", "on", "off", "null", "~", "y", "n":
		return true
	}
	if _, err := strconv.ParseInt(s, 0, 64); err == nil {
		return true
	}
	if _, err := strconv.ParseFloat(s, 64); err == nil {
		return true
	}
	return false
}

func quoteString(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '\\':
			b.WriteString(`\\`)
		case '"':
			b.WriteString(`\"`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 || r == 0x7f {
				fmt.Fprintf(&b, `\x%02X`, r)
			} else {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
	return b.String()
}
