package parser

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"

	"proxypool/internal/model"
)

// splitFragment 拆出 # 后面的节点名。
func splitFragment(s string) (string, string) {
	if i := strings.Index(s, "#"); i >= 0 {
		return s[:i], decodeComponent(s[i+1:])
	}
	return s, ""
}

// ---------- Shadowsocks ----------

func parseSS(link, source string) (*model.Node, error) {
	rest := link[strings.Index(link, "://")+3:]
	var frag string
	rest, frag = splitFragment(rest)

	var query string
	if i := strings.Index(rest, "?"); i >= 0 {
		query, rest = rest[i+1:], rest[:i]
	}

	var method, password, hostPart string
	if i := strings.LastIndex(rest, "@"); i >= 0 {
		userinfo, hp := rest[:i], rest[i+1:]
		hostPart = hp
		if dec, ok := tryBase64Min(userinfo, 4); ok && strings.Contains(string(dec), ":") {
			userinfo = string(dec)
		} else {
			userinfo = decodeComponent(userinfo)
		}
		method, password = cutColon(userinfo)
	} else {
		dec, ok := tryBase64(rest)
		if !ok {
			return nil, errors.New("ss 链接格式无法识别")
		}
		full := string(dec)
		at := strings.LastIndex(full, "@")
		if at < 0 {
			return nil, errors.New("ss 链接缺少 server:port")
		}
		method, password = cutColon(decodeComponent(full[:at]))
		hostPart = full[at+1:]
	}

	host, port, err := splitHostPort(hostPart)
	if err != nil {
		return nil, err
	}
	if method == "" || password == "" {
		return nil, errors.New("ss 链接缺少加密方式或密码")
	}

	node := newNode(source, "ss", frag, host, port)
	raw := node.Raw
	raw["cipher"] = strings.ToLower(strings.TrimSpace(method))
	raw["password"] = password

	if query != "" {
		if q, err := url.ParseQuery(query); err == nil {
			if plugin := q.Get("plugin"); plugin != "" {
				applySSPlugin(raw, decodeComponent(plugin))
			}
		}
	}
	return node, nil
}

func applySSPlugin(raw map[string]any, plugin string) {
	if strings.TrimSpace(plugin) == "" {
		return
	}
	segs := strings.Split(plugin, ";")
	name := strings.ToLower(strings.TrimSpace(segs[0]))
	opts := map[string]string{}
	for _, seg := range segs[1:] {
		k, v, ok := strings.Cut(seg, "=")
		if !ok {
			continue
		}
		opts[strings.ToLower(strings.TrimSpace(k))] = strings.TrimSpace(v)
	}

	switch {
	case name == "obfs-local" || name == "simple-obfs" || name == "obfs":
		pluginOpts := map[string]any{}
		mode := strings.ToLower(opts["obfs"])
		if mode == "" {
			mode = "http"
		}
		pluginOpts["mode"] = mode
		if h := opts["obfs-host"]; h != "" {
			pluginOpts["host"] = h
		}
		raw["plugin"] = "obfs"
		raw["plugin-opts"] = pluginOpts
	case name == "v2ray-plugin":
		pluginOpts := map[string]any{"mode": "websocket", "mux": false}
		if h := opts["host"]; h != "" {
			pluginOpts["host"] = h
		}
		if p := opts["path"]; p != "" {
			pluginOpts["path"] = p
		}
		if _, ok := opts["tls"]; ok {
			pluginOpts["tls"] = true
		}
		raw["plugin"] = "v2ray-plugin"
		raw["plugin-opts"] = pluginOpts
	}
}

// ---------- ShadowsocksR ----------

func parseSSR(link, source string) (*model.Node, error) {
	body := link[strings.Index(link, "://")+3:]
	body, frag := splitFragment(body)
	dec, ok := tryBase64(body)
	if !ok {
		return nil, errors.New("ssr 链接 Base64 解码失败")
	}
	text := string(dec)

	var params url.Values
	if i := strings.Index(text, "/?"); i >= 0 {
		params, _ = url.ParseQuery(text[i+2:])
		text = text[:i]
	} else if i := strings.Index(text, "?"); i >= 0 {
		params, _ = url.ParseQuery(text[i+1:])
		text = text[:i]
	}
	if params == nil {
		params = url.Values{}
	}

	parts := strings.Split(text, ":")
	if len(parts) < 6 {
		return nil, errors.New("ssr 链接字段不足")
	}
	passwordB64 := parts[len(parts)-1]
	obfs := parts[len(parts)-2]
	method := parts[len(parts)-3]
	protocol := parts[len(parts)-4]
	portStr := parts[len(parts)-5]
	host := strings.Join(parts[:len(parts)-5], ":")

	port := atoiSafe(portStr)
	if host == "" || port <= 0 {
		return nil, errors.New("ssr 链接缺少 server:port")
	}
	password := decodeB64String(passwordB64)
	if password == "" {
		return nil, errors.New("ssr 链接缺少密码")
	}
	if frag == "" {
		frag = decodeB64String(params.Get("remarks"))
	}

	node := newNode(source, "ssr", frag, host, port)
	raw := node.Raw
	raw["cipher"] = strings.ToLower(method)
	raw["password"] = password
	raw["protocol"] = strings.ToLower(protocol)
	raw["obfs"] = strings.ToLower(obfs)
	if v := decodeB64String(params.Get("obfsparam")); v != "" {
		raw["obfs-param"] = v
	}
	if v := decodeB64String(params.Get("protoparam")); v != "" {
		raw["protocol-param"] = v
	}
	return node, nil
}

func decodeB64String(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if dec, ok := tryBase64Min(s, 2); ok {
		return string(dec)
	}
	return decodeComponent(s)
}

// ---------- VMess ----------

func parseVmess(link, source string) (*model.Node, error) {
	body := link[strings.Index(link, "://")+3:]
	body, frag := splitFragment(body)
	dec, ok := tryBase64(body)
	if !ok {
		return nil, errors.New("vmess 链接 Base64 解码失败")
	}
	var m map[string]any
	if err := json.Unmarshal(dec, &m); err != nil {
		return nil, fmt.Errorf("vmess JSON 解析失败: %w", err)
	}

	server := model.GetString(m, "add")
	if server == "" {
		return nil, errors.New("vmess 缺少服务器地址")
	}
	port := model.GetInt(m, "port")
	if port <= 0 {
		return nil, errors.New("vmess 缺少端口")
	}
	uuid := model.GetString(m, "id")
	if uuid == "" {
		return nil, errors.New("vmess 缺少 uuid")
	}
	name := frag
	if name == "" {
		name = model.GetString(m, "ps")
	}

	node := newNode(source, "vmess", name, server, port)
	raw := node.Raw
	raw["uuid"] = uuid
	raw["alterId"] = model.GetInt(m, "aid")
	cipher := strings.ToLower(model.GetString(m, "scy"))
	if cipher == "" {
		cipher = "auto"
	}
	raw["cipher"] = cipher

	network := strings.ToLower(model.GetString(m, "net"))
	if network == "" {
		network = "tcp"
	}
	headerType := strings.ToLower(model.GetString(m, "type"))
	host := model.GetString(m, "host")
	path := model.GetString(m, "path")

	switch network {
	case "tcp":
		if headerType == "http" {
			raw["network"] = "http"
			opts := map[string]any{}
			headers := map[string]any{}
			if host != "" {
				headers["Host"] = []string{host}
			}
			if len(headers) > 0 {
				opts["headers"] = headers
			}
			if path != "" {
				opts["path"] = []string{path}
			}
			raw["http-opts"] = opts
		}
	case "ws":
		raw["network"] = "ws"
		opts := map[string]any{}
		if path != "" {
			opts["path"] = path
		}
		if host != "" {
			opts["headers"] = map[string]any{"Host": host}
		}
		if len(opts) > 0 {
			raw["ws-opts"] = opts
		}
	case "grpc":
		raw["network"] = "grpc"
		if path != "" {
			raw["grpc-opts"] = map[string]any{"grpc-service-name": path}
		}
	case "h2", "http":
		raw["network"] = "h2"
		opts := map[string]any{}
		if path != "" {
			opts["path"] = path
		}
		if host != "" {
			opts["host"] = []string{host}
		}
		if len(opts) > 0 {
			raw["h2-opts"] = opts
		}
	default:
		return nil, fmt.Errorf("暂不支持的 vmess 传输方式: %s", network)
	}

	if strings.EqualFold(model.GetString(m, "tls"), "tls") {
		raw["tls"] = true
	}
	if sni := model.GetString(m, "sni"); sni != "" {
		raw["servername"] = sni
	}
	if fp := model.GetString(m, "fp"); fp != "" {
		raw["client-fingerprint"] = fp
	}
	if alpn := splitList(model.GetString(m, "alpn")); len(alpn) > 0 {
		raw["alpn"] = alpn
	}
	if model.GetBool(m, "tls") || model.GetString(m, "sni") != "" {
		raw["skip-cert-verify"] = true
	}
	return node, nil
}

// ---------- VLESS ----------

func parseVless(link, source string) (*model.Node, error) {
	lp, err := splitLink(link)
	if err != nil {
		return nil, err
	}
	uuid := decodeComponent(lp.user)
	if uuid == "" {
		return nil, errors.New("vless 缺少 uuid")
	}
	node := newNode(source, "vless", lp.frag, lp.host, lp.port)
	raw := node.Raw
	raw["uuid"] = uuid
	if flow := lp.q("flow"); flow != "" {
		raw["flow"] = flow
	}

	security := strings.ToLower(lp.q("security"))
	switch security {
	case "tls":
		raw["tls"] = true
	case "reality":
		raw["tls"] = true
		opts := map[string]any{}
		if pbk := lp.q("pbk", "publicKey"); pbk != "" {
			opts["public-key"] = pbk
		}
		if sid := lp.q("sid", "shortId"); sid != "" {
			opts["short-id"] = sid
		}
		if len(opts) > 0 {
			raw["reality-opts"] = opts
		}
	case "xtls":
		raw["tls"] = true
	}
	if security == "tls" || security == "reality" || security == "xtls" {
		if sni := lp.q("sni", "peer"); sni != "" {
			raw["servername"] = sni
		} else if host := lp.q("host"); host != "" {
			raw["servername"] = host
		}
		if lp.qBool("allowInsecure", "insecure", "skip-cert-verify") {
			raw["skip-cert-verify"] = true
		}
	}
	if fp := lp.q("fp", "client-fingerprint"); fp != "" {
		raw["client-fingerprint"] = fp
	}
	if alpn := splitList(lp.q("alpn")); len(alpn) > 0 {
		raw["alpn"] = alpn
	}

	network := strings.ToLower(lp.q("type"))
	if network == "" {
		network = "tcp"
	}
	path := lp.q("path")
	host := lp.q("host")
	switch network {
	case "ws":
		raw["network"] = "ws"
		opts := map[string]any{}
		if path != "" {
			opts["path"] = path
		}
		if host != "" {
			opts["headers"] = map[string]any{"Host": host}
		}
		if len(opts) > 0 {
			raw["ws-opts"] = opts
		}
	case "grpc":
		raw["network"] = "grpc"
		service := lp.q("serviceName", "service-name")
		if service == "" {
			service = path
		}
		if service != "" {
			raw["grpc-opts"] = map[string]any{"grpc-service-name": service}
		}
	case "h2", "http":
		raw["network"] = "h2"
		opts := map[string]any{}
		if path != "" {
			opts["path"] = path
		}
		if host != "" {
			opts["host"] = []string{host}
		}
		if len(opts) > 0 {
			raw["h2-opts"] = opts
		}
	case "tcp":
		// 保持默认
	default:
		return nil, fmt.Errorf("暂不支持的 vless 传输方式: %s", network)
	}
	return node, nil
}

// ---------- Trojan ----------

func parseTrojan(link, source string) (*model.Node, error) {
	lp, err := splitLink(link)
	if err != nil {
		return nil, err
	}
	password := decodeComponent(lp.user)
	if password == "" {
		return nil, errors.New("trojan 缺少密码")
	}
	node := newNode(source, "trojan", lp.frag, lp.host, lp.port)
	raw := node.Raw
	raw["password"] = password
	if sni := lp.q("sni", "peer"); sni != "" {
		raw["sni"] = sni
	} else {
		raw["sni"] = lp.host
	}
	if lp.qBool("allowInsecure", "insecure", "skip-cert-verify") {
		raw["skip-cert-verify"] = true
	}
	if alpn := splitList(lp.q("alpn")); len(alpn) > 0 {
		raw["alpn"] = alpn
	}
	if fp := lp.q("fp"); fp != "" {
		raw["client-fingerprint"] = fp
	}

	network := strings.ToLower(lp.q("type"))
	switch network {
	case "ws":
		raw["network"] = "ws"
		opts := map[string]any{}
		if path := lp.q("path"); path != "" {
			opts["path"] = path
		}
		if host := lp.q("host"); host != "" {
			opts["headers"] = map[string]any{"Host": host}
		}
		if len(opts) > 0 {
			raw["ws-opts"] = opts
		}
	case "grpc":
		raw["network"] = "grpc"
		service := lp.q("serviceName")
		if service == "" {
			service = lp.q("path")
		}
		if service != "" {
			raw["grpc-opts"] = map[string]any{"grpc-service-name": service}
		}
	}
	return node, nil
}

// ---------- Hysteria2 ----------

func parseHysteria2(link, source string) (*model.Node, error) {
	lp, err := splitLink(link)
	if err != nil {
		return nil, err
	}
	user, pass := lp.userParts()
	password := pass
	if password == "" {
		password = user
	}
	if password == "" {
		return nil, errors.New("hysteria2 缺少密码")
	}
	node := newNode(source, "hysteria2", lp.frag, lp.host, lp.port)
	raw := node.Raw
	raw["password"] = password
	if sni := lp.q("sni", "peer"); sni != "" {
		raw["sni"] = sni
	}
	if lp.qBool("insecure", "allowInsecure", "skip-cert-verify") {
		raw["skip-cert-verify"] = true
	}
	if obfs := lp.q("obfs"); obfs != "" {
		raw["obfs"] = obfs
		if p := lp.q("obfs-password", "obfs_password"); p != "" {
			raw["obfs-password"] = p
		}
	}
	if alpn := splitList(lp.q("alpn")); len(alpn) > 0 {
		raw["alpn"] = alpn
	}
	if up := lp.q("up", "upmbps"); up != "" {
		raw["up"] = up
	}
	if down := lp.q("down", "downmbps"); down != "" {
		raw["down"] = down
	}
	if fp := lp.q("pinSHA256", "fingerprint"); fp != "" {
		raw["fingerprint"] = fp
	}
	return node, nil
}

// ---------- Hysteria v1 ----------

func parseHysteria(link, source string) (*model.Node, error) {
	lp, err := splitLink(link)
	if err != nil {
		return nil, err
	}
	node := newNode(source, "hysteria", lp.frag, lp.host, lp.port)
	raw := node.Raw
	auth := lp.q("auth", "auth_str", "authStr")
	if auth == "" {
		auth = decodeComponent(lp.user)
	}
	if auth == "" {
		return nil, errors.New("hysteria 缺少认证信息")
	}
	raw["auth-str"] = auth
	if protocol := lp.q("protocol"); protocol != "" {
		raw["protocol"] = protocol
	} else {
		raw["protocol"] = "udp"
	}
	if up := lp.q("upmbps", "up"); up != "" {
		raw["up"] = up
	}
	if down := lp.q("downmbps", "down"); down != "" {
		raw["down"] = down
	}
	if sni := lp.q("peer", "sni"); sni != "" {
		raw["sni"] = sni
	}
	if lp.qBool("insecure", "allowInsecure") {
		raw["skip-cert-verify"] = true
	}
	if alpn := splitList(lp.q("alpn")); len(alpn) > 0 {
		raw["alpn"] = alpn
	} else {
		raw["alpn"] = []string{"h3"}
	}
	if obfs := lp.q("obfs"); obfs != "" {
		raw["obfs"] = obfs
	}
	return node, nil
}

// ---------- TUIC ----------

func parseTuic(link, source string) (*model.Node, error) {
	lp, err := splitLink(link)
	if err != nil {
		return nil, err
	}
	uuid, password := lp.userParts()
	if uuid == "" {
		return nil, errors.New("tuic 缺少 uuid")
	}
	node := newNode(source, "tuic", lp.frag, lp.host, lp.port)
	raw := node.Raw
	raw["uuid"] = uuid
	raw["password"] = password
	if sni := lp.q("sni", "peer"); sni != "" {
		raw["sni"] = sni
	}
	if lp.qBool("allow_insecure", "insecure", "skip-cert-verify") {
		raw["skip-cert-verify"] = true
	}
	if cc := lp.q("congestion_control", "congestion-controller"); cc != "" {
		raw["congestion-controller"] = cc
	}
	if mode := lp.q("udp_relay_mode", "udp-relay-mode"); mode != "" {
		raw["udp-relay-mode"] = mode
	}
	if lp.qBool("disable_sni") {
		raw["disable-sni"] = true
	}
	if lp.qBool("reduce_rtt") {
		raw["reduce-rtt"] = true
	}
	if alpn := splitList(lp.q("alpn")); len(alpn) > 0 {
		raw["alpn"] = alpn
	}
	return node, nil
}

// ---------- SOCKS5 ----------

func parseSocks5(link, source string) (*model.Node, error) {
	lp, err := splitLink(link)
	if err != nil {
		return nil, err
	}
	node := newNode(source, "socks5", lp.frag, lp.host, lp.port)
	username, password := lp.userParts()
	if username != "" {
		node.Raw["username"] = username
	}
	if password != "" {
		node.Raw["password"] = password
	}
	return node, nil
}
