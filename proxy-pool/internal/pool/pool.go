// Package pool 负责把多个订阅的节点合并成一个节点池：过滤非法节点、去重、识别地区、统一命名。
package pool

import (
	"fmt"
	"sort"
	"strconv"
	"strings"

	"proxypool/internal/config"
	"proxypool/internal/model"
)

// Result 节点池构建结果。
type Result struct {
	Nodes      []*model.Node
	Invalid    int
	Dropped    int
	Duplicated int
}

// supportedTypes mihomo 内核支持的代理类型白名单。
var supportedTypes = map[string]bool{
	"ss": true, "ssr": true, "vmess": true, "vless": true, "trojan": true,
	"hysteria": true, "hysteria2": true, "tuic": true, "snell": true,
	"socks5": true, "http": true, "wireguard": true, "ssh": true,
	"anytls": true, "mieru": true, "direct": true, "dns": true,
}

type regionInfo struct {
	code     string
	name     string
	emoji    string
	keywords []string
}

// 顺序即匹配优先级（越靠前越优先），ASCII 关键词按单词边界匹配，中文按子串匹配。
var regions = []regionInfo{
	{"HK", "香港", "🇭🇰", []string{"香港", "深港", "沪港", "京港", "港", "hongkong", "hong kong", "hkg", "hkbn", "hkt", "cmi", "hk"}},
	{"TW", "台湾", "🇹🇼", []string{"台湾", "台北", "新北", "彰化", "台中", "taiwan", "taipei", "tpe", "tw"}},
	{"JP", "日本", "🇯🇵", []string{"日本", "东京", "大阪", "埼玉", "名古屋", "japan", "tokyo", "osaka", "nrt", "jpn", "jp"}},
	{"SG", "新加坡", "🇸🇬", []string{"新加坡", "狮城", "singapore", "sin", "sgp", "sg"}},
	{"US", "美国", "🇺🇸", []string{"美国", "洛杉矶", "圣何塞", "西雅图", "达拉斯", "纽约", "芝加哥", "凤凰城", "united states", "los angeles", "san jose", "seattle", "dallas", "new york", "chicago", "phoenix", "us"}},
	{"KR", "韩国", "🇰🇷", []string{"韩国", "首尔", "korea", "seoul", "icn", "kr"}},
	{"GB", "英国", "🇬🇧", []string{"英国", "伦敦", "united kingdom", "london", "uk"}},
	{"DE", "德国", "🇩🇪", []string{"德国", "法兰克福", "germany", "frankfurt", "de"}},
	{"FR", "法国", "🇫🇷", []string{"法国", "巴黎", "france", "paris", "fr"}},
	{"NL", "荷兰", "🇳🇱", []string{"荷兰", "阿姆斯特丹", "netherlands", "amsterdam", "nl"}},
	{"CA", "加拿大", "🇨🇦", []string{"加拿大", "多伦多", "蒙特利尔", "canada", "toronto", "montreal", "ca"}},
	{"AU", "澳大利亚", "🇦🇺", []string{"澳大利亚", "澳洲", "悉尼", "australia", "sydney", "au"}},
	{"IN", "印度", "🇮🇳", []string{"印度", "孟买", "india", "mumbai", "in"}},
	{"RU", "俄罗斯", "🇷🇺", []string{"俄罗斯", "莫斯科", "russia", "moscow", "ru"}},
	{"TR", "土耳其", "🇹🇷", []string{"土耳其", "turkey", "istanbul", "tr"}},
	{"AE", "阿联酋", "🇦🇪", []string{"阿联酋", "迪拜", "emirates", "dubai", "ae"}},
	{"MY", "马来西亚", "🇲🇾", []string{"马来西亚", "马来", "malaysia", "kuala", "my"}},
	{"TH", "泰国", "🇹🇭", []string{"泰国", "曼谷", "thailand", "bangkok", "th"}},
	{"VN", "越南", "🇻🇳", []string{"越南", "vietnam", "vn"}},
	{"PH", "菲律宾", "🇵🇭", []string{"菲律宾", "philippines", "manila", "ph"}},
	{"ID", "印尼", "🇮🇩", []string{"印尼", "印度尼西亚", "indonesia", "jakarta", "id"}},
	{"CH", "瑞士", "🇨🇭", []string{"瑞士", "switzerland", "zurich", "ch"}},
	{"SE", "瑞典", "🇸🇪", []string{"瑞典", "sweden", "stockholm", "se"}},
	{"ES", "西班牙", "🇪🇸", []string{"西班牙", "spain", "madrid", "es"}},
	{"IT", "意大利", "🇮🇹", []string{"意大利", "italy", "milan", "it"}},
	{"PL", "波兰", "🇵🇱", []string{"波兰", "poland", "warsaw", "pl"}},
	{"BR", "巴西", "🇧🇷", []string{"巴西", "brazil", "sao paulo", "br"}},
	{"AR", "阿根廷", "🇦🇷", []string{"阿根廷", "argentina", "ar"}},
	{"CN", "中国", "🇨🇳", []string{"中国", "国内", "北京", "上海", "广州", "深圳", "杭州", "成都", "china", "cn"}},
	{"ZZ", "其他", "🏳️", nil},
}

// DetectRegion 根据节点名/服务器识别地区。
func DetectRegion(name, server string) (code, region, emoji string) {
	text := strings.ToLower(name + " " + server)
	for _, r := range regions {
		for _, kw := range r.keywords {
			if kw == "" {
				continue
			}
			if isASCII(kw) {
				if containsToken(text, kw) {
					return r.code, r.name, r.emoji
				}
			} else if strings.Contains(text, strings.ToLower(kw)) {
				return r.code, r.name, r.emoji
			}
		}
	}
	return "ZZ", "其他", "🏳️"
}

func isASCII(s string) bool {
	for _, r := range s {
		if r > 127 {
			return false
		}
	}
	return true
}

// containsToken 按单词边界匹配 ASCII 关键词，避免 "in" 命中 "singapore"。
func containsToken(text, token string) bool {
	from := 0
	for {
		idx := strings.Index(text[from:], token)
		if idx < 0 {
			return false
		}
		idx += from
		beforeOK := idx == 0 || !isAlnum(rune(text[idx-1]))
		after := idx + len(token)
		afterOK := after >= len(text) || !isAlnum(rune(text[after]))
		if beforeOK && afterOK {
			return true
		}
		from = idx + 1
		if from >= len(text) {
			return false
		}
	}
}

func isAlnum(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
}

// Build 合并节点池。
func Build(nodes []*model.Node, cfg config.PoolConfig) *Result {
	res := &Result{}
	dropExtra := map[string]bool{}
	for _, t := range cfg.DropTypes {
		dropExtra[strings.ToLower(strings.TrimSpace(t))] = true
	}

	seen := map[string]*model.Node{}
	kept := make([]*model.Node, 0, len(nodes))

	for _, n := range nodes {
		if n == nil || len(n.Raw) == 0 {
			res.Invalid++
			continue
		}
		if err := n.Valid(); err != nil {
			res.Invalid++
			continue
		}
		typ := n.Type()
		if dropExtra[typ] || !supportedTypes[typ] {
			res.Dropped++
			continue
		}
		if cfg.Dedupe {
			fp := n.Fingerprint()
			if exist, ok := seen[fp]; ok {
				res.Duplicated++
				_ = exist
				continue
			}
			seen[fp] = n
		}
		if n.OriginName == "" {
			n.OriginName = n.Name()
		}
		kept = append(kept, n)
	}

	// 保持订阅顺序的前提下，按地区聚拢命名
	sort.SliceStable(kept, func(i, j int) bool {
		return kept[i].Source < kept[j].Source
	})

	format := cfg.NameFormat
	if strings.TrimSpace(format) == "" {
		format = "{emoji}{code}-{index:03d}"
	}
	used := map[string]int{}
	for i, n := range kept {
		code, region, emoji := DetectRegion(n.OriginName, n.Server())
		n.RegionCode, n.Region, n.Emoji = code, region, emoji
		name := applyFormat(format, n, i+1)
		name = strings.TrimSpace(name)
		if name == "" {
			name = fmt.Sprintf("%s-%03d", code, i+1)
		}
		if cnt := used[name]; cnt > 0 {
			used[name] = cnt + 1
			name = fmt.Sprintf("%s-%d", name, cnt+1)
			used[name] = 1
		} else {
			used[name] = 1
		}
		n.SetName(name)
	}

	res.Nodes = kept
	return res
}

// applyFormat 替换命名模板。支持 {emoji} {code} {region} {index} {index:03d} {source} {name}。
func applyFormat(format string, n *model.Node, index int) string {
	replacer := strings.NewReplacer(
		"{emoji}", n.Emoji,
		"{code}", n.RegionCode,
		"{region}", n.Region,
		"{index:03d}", fmt.Sprintf("%03d", index),
		"{index:03}", fmt.Sprintf("%03d", index),
		"{index}", strconv.Itoa(index),
		"{source}", n.Source,
		"{name}", n.OriginName,
	)
	return replacer.Replace(format)
}
