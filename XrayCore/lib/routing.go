package lib

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/xtls/xray-core/app/router"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/inbound"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/infra/conf"
	jsonreader "github.com/xtls/xray-core/infra/conf/json"
	"github.com/xtls/xray-core/proxy"
	"github.com/xtls/xray-core/proxy/socks"
)

// Retired handlers may own multiplexers and transport goroutines. They cannot be
// closed safely until Stop: Xray has no general per-handler flow-drain primitive.
// Bound retained *real* versions, not rule-only updates or zero-resource aliases.
const maxLiveOutboundVersions = 512

var routingState *liveRoutingState

type liveOutbound struct {
	key      string
	config   map[string]any
	physical string
	version  string
	handler  outbound.Handler
	original bool
}

type liveRoutingState struct {
	config     map[string]any
	outbounds  map[string]*liveOutbound
	originals  map[string]bool
	prefix     string
	generation uint64
	retained   int
	health     *liveHealth
}

// A unique alias makes balancer prefix selection exact, including when logical
// names overlap. It does not start/close its delegate twice; the original manager
// entry owns the real handler throughout this core's lifetime.
type outboundAlias struct {
	outbound.Handler
	tag string
}

func (h *outboundAlias) Tag() string             { return h.tag }
func (h *outboundAlias) Start() error            { return nil }
func (h *outboundAlias) Close() error            { return nil }
func (h *outboundAlias) ExactSelectorOnly() bool { return true }

// Real versioned handlers own their resources normally, but remain invisible to
// an old broad-prefix balancer while a candidate transaction is being staged.
type exactOutboundHandler struct {
	outbound.Handler
}

func (h *exactOutboundHandler) ExactSelectorOnly() bool { return true }

func decodeRoutingDocument(text []byte) (map[string]any, error) {
	decoder := json.NewDecoder(&jsonreader.Reader{Reader: bytes.NewReader(text)})
	decoder.UseNumber()
	var document map[string]any
	if err := decoder.Decode(&document); err != nil || document == nil {
		return nil, errors.New("invalid live routing JSON object")
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return nil, errors.New("live routing config must contain exactly one JSON object")
	}
	return document, nil
}

func object(value any) map[string]any {
	result, _ := value.(map[string]any)
	return result
}

func stringValue(value any) string {
	result, _ := value.(string)
	return result
}

func outboundDocuments(document map[string]any) ([]*liveOutbound, error) {
	values, ok := document["outbounds"].([]any)
	if !ok || len(values) == 0 {
		return nil, errors.New("live routing requires at least one outbound")
	}
	result := make([]*liveOutbound, 0, len(values))
	seen := make(map[string]bool, len(values))
	for i, value := range values {
		config := object(value)
		if config == nil {
			return nil, errors.New("outbound must be a JSON object")
		}
		key := stringValue(config["tag"])
		if key == "" {
			key = "\x00untagged." + strconv.Itoa(i)
		}
		if seen[key] {
			return nil, errors.New("duplicate outbound tag")
		}
		seen[key] = true
		result = append(result, &liveOutbound{key: key, config: config})
	}
	return result, nil
}

func newRoutingState(document map[string]any, server *core.Instance, config *core.Config) (*liveRoutingState, error) {
	outbounds, err := outboundDocuments(document)
	if err != nil {
		// Start still accepts cores without outbounds. ApplyRouting will reject
		// those configurations rather than silently changing their semantics.
		return &liveRoutingState{config: document}, nil
	}
	var nonce [16]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, errors.New("cannot allocate live routing namespace")
	}
	state := &liveRoutingState{
		config: document, outbounds: make(map[string]*liveOutbound, len(outbounds)),
		originals: make(map[string]bool, len(outbounds)),
		prefix:    "_yaxc.live." + hex.EncodeToString(nonce[:]) + ".", retained: len(outbounds),
	}
	manager := server.GetFeature(outbound.ManagerType()).(outbound.Manager)
	var untagged []outbound.Handler
	for _, handler := range manager.ListHandlers(context.Background()) {
		if handler.Tag() == "" {
			untagged = append(untagged, handler)
		}
	}
	untaggedIndex := 0
	for i, entry := range outbounds {
		tag := config.Outbound[i].Tag
		if tag == "" {
			// The manager retains untagged handlers in configuration order.
			// Keep them untagged so existing prefix selectors cannot see them.
			if untaggedIndex < len(untagged) {
				entry.handler = untagged[untaggedIndex]
				untaggedIndex++
			}
		} else {
			entry.handler = manager.GetHandler(tag)
		}
		if entry.handler == nil {
			return nil, errors.New("cannot locate initial outbound handler")
		}
		entry.original = true
		entry.version = entry.key
		state.originals[entry.key] = true
		state.outbounds[entry.key] = entry
	}
	return state, nil
}

func staticRoutingDocument(document map[string]any) map[string]any {
	result := make(map[string]any, len(document))
	for key, value := range document {
		if key != "routing" && key != "outbounds" && key != "inbounds" && key != liveHealthMetadata {
			result[key] = value
		}
	}
	routingSettings := make(map[string]any)
	for key, value := range object(document["routing"]) {
		if key != "rules" && key != "balancers" {
			routingSettings[key] = value
		}
	}
	result["routing"] = routingSettings
	if values, ok := document["inbounds"].([]any); ok {
		inbounds := make([]any, len(values))
		for i, value := range values {
			original := object(value)
			if stringValue(original["protocol"]) != "socks" && stringValue(original["protocol"]) != "mixed" {
				inbounds[i] = value
				continue
			}
			entry := make(map[string]any, len(original))
			for key, field := range original {
				entry[key] = field
			}
			if settings := object(original["settings"]); settings != nil {
				copy := make(map[string]any, len(settings))
				for key, field := range settings {
					if key != "accounts" {
						copy[key] = field
					}
				}
				entry["settings"] = copy
			}
			inbounds[i] = entry
		}
		result["inbounds"] = inbounds
	} else if value, exists := document["inbounds"]; exists {
		result["inbounds"] = value
	}
	return result
}

func uidAccount(username string) bool {
	const prefix = "yaxc.uid."
	if !strings.HasPrefix(username, prefix) {
		return false
	}
	uid := strings.TrimPrefix(username, prefix)
	value, err := strconv.ParseUint(uid, 10, 31)
	return err == nil && strconv.FormatUint(value, 10) == uid
}

func socksAccounts(config map[string]any) (map[string]string, error) {
	accounts := make(map[string]string)
	settings := object(config["settings"])
	value, exists := settings["accounts"]
	if !exists {
		value, exists = settings["users"]
	}
	if exists {
		values, ok := value.([]any)
		if !ok {
			return nil, errors.New("SOCKS accounts must be an array")
		}
		for _, value := range values {
			account := object(value)
			user, userOK := account["user"].(string)
			password, passwordOK := account["pass"].(string)
			if !userOK || !passwordOK || user == "" || len(user) > 255 || len(password) == 0 || len(password) > 255 {
				return nil, errors.New("invalid SOCKS account encoding")
			}
			if _, exists := accounts[user]; exists {
				return nil, errors.New("duplicate SOCKS account")
			}
			accounts[user] = password
		}
	}
	return accounts, nil
}

type accountReplacement struct {
	server   *socks.Server
	accounts map[string]string
}

func prepareAccounts(server *core.Instance, before, after map[string]any) ([]accountReplacement, error) {
	oldValues, _ := before["inbounds"].([]any)
	newValues, _ := after["inbounds"].([]any)
	var replacements []accountReplacement
	manager := server.GetFeature(inbound.ManagerType()).(inbound.Manager)
	for i, value := range newValues {
		entry := object(value)
		protocol := stringValue(entry["protocol"])
		if protocol != "socks" && protocol != "mixed" {
			continue
		}
		oldAccounts, err := socksAccounts(object(oldValues[i]))
		if err != nil {
			return nil, err
		}
		newAccounts, err := socksAccounts(entry)
		if err != nil {
			return nil, err
		}
		if reflect.DeepEqual(oldAccounts, newAccounts) {
			continue
		}
		passwords := make(map[string]bool)
		for user, password := range oldAccounts {
			if !uidAccount(user) {
				if newAccounts[user] != password {
					return nil, errors.New("default SOCKS credentials require a restart")
				}
				passwords[password] = true
			}
		}
		for user, password := range newAccounts {
			if !uidAccount(user) {
				if oldAccounts[user] != password {
					return nil, errors.New("only per-UID SOCKS accounts can change live")
				}
			} else if !passwords[password] {
				return nil, errors.New("per-UID SOCKS accounts must use the default password")
			}
		}
		if stringValue(object(entry["settings"])["auth"]) != "password" {
			return nil, errors.New("live SOCKS accounts require password authentication")
		}
		handler, err := manager.GetHandler(context.Background(), stringValue(entry["tag"]))
		if err != nil {
			return nil, errors.New("cannot locate SOCKS inbound for live accounts")
		}
		provider, ok := handler.(interface{ GetInbound() proxy.Inbound })
		if !ok {
			return nil, errors.New("SOCKS inbound does not support live accounts")
		}
		instance, ok := provider.GetInbound().(*socks.Server)
		if !ok {
			return nil, errors.New("inbound is not a SOCKS server")
		}
		replacements = append(replacements, accountReplacement{instance, newAccounts})
	}
	return replacements, nil
}

func walkStreamDialers(stream map[string]any, visit func(map[string]any, string) error) error {
	sockopt := object(stream["sockopt"])
	if tag := stringValue(sockopt["dialerProxy"]); tag != "" {
		if err := visit(sockopt, tag); err != nil {
			return err
		}
	}
	// Only these fields contain nested StreamConfig objects. Header/provider
	// objects can legitimately have a key named dialerProxy and are not refs.
	for _, key := range []string{"xhttpSettings", "splithttpSettings"} {
		settings := object(stream[key])
		for _, download := range []any{settings["downloadSettings"], object(settings["extra"])["downloadSettings"]} {
			if nested := object(download); nested != nil {
				if err := walkStreamDialers(nested, visit); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func outboundDependencies(value map[string]any, visit func(string) error) error {
	return walkStreamDialers(object(value["streamSettings"]), func(_ map[string]any, tag string) error {
		return visit(tag)
	})
}

func rewriteOutboundDependencies(value map[string]any, entries map[string]*liveOutbound) {
	_ = walkStreamDialers(object(value["streamSettings"]), func(sockopt map[string]any, tag string) error {
		sockopt["dialerProxy"] = entries[tag].physical
		return nil
	})
}

func liveRouterConfig(document map[string]any, entries map[string]*liveOutbound, state *liveRoutingState, manager outbound.Manager) (*router.Config, error) {
	var source conf.RouterConfig
	if value, exists := document["routing"]; exists {
		encoded, err := json.Marshal(value)
		if err != nil {
			return nil, err
		}
		if err := json.Unmarshal(encoded, &source); err != nil {
			return nil, errors.New("invalid routing configuration")
		}
	}
	config, err := source.Build()
	if err != nil {
		return nil, err
	}
	if err := inheritDefaultUserRules(config, document); err != nil {
		return nil, err
	}
	resolve := func(tag string) (string, error) {
		entry := entries[tag]
		if entry == nil {
			// API/metrics services register real handlers outside JSON outbounds.
			// Removed managed handlers and private retained versions are never service targets.
			if tag != "" && !state.originals[tag] && !strings.HasPrefix(tag, state.prefix) && manager.GetHandler(tag) != nil {
				return tag, nil
			}
			return "", errors.New("routing references an unavailable outbound")
		}
		return entry.physical, nil
	}
	for _, rule := range config.Rule {
		if target, ok := rule.TargetTag.(*router.RoutingRule_Tag); ok {
			if target.Tag, err = resolve(target.Tag); err != nil {
				return nil, err
			}
		}
		// Upstream's attributes builder uses MustCompile; reject instead of
		// allowing malformed input to panic inside the app's native boundary.
		for _, pattern := range rule.Attributes {
			if _, err := regexp.Compile(pattern); err != nil {
				return nil, errors.New("invalid routing attribute regular expression")
			}
		}
	}
	for _, balancer := range config.BalancingRule {
		healthBased := strings.EqualFold(balancer.Strategy, "leastping") ||
			strings.EqualFold(balancer.Strategy, "leastload") || balancer.FallbackTag != ""
		matches := func(tag string) bool {
			for _, selector := range balancer.OutboundSelector {
				if strings.HasPrefix(tag, selector) {
					return true
				}
			}
			return false
		}
		if healthBased {
			// Observatory results use logical tags. Preserve those tags while the
			// observed handlers are unchanged; never apply stale health to a new version.
			selector, ok := manager.(outbound.HandlerSelector)
			if !ok {
				return nil, errors.New("outbound manager cannot select observed members")
			}
			for _, tag := range selector.Select(balancer.OutboundSelector) {
				if state.originals[tag] && (entries[tag] == nil || !entries[tag].original) {
					return nil, errors.New("changing observatory-backed balancer members requires an explicit reconnect")
				}
			}
			for key, entry := range entries {
				if matches(key) && !entry.original {
					return nil, errors.New("changing observatory-backed balancer members requires an explicit reconnect")
				}
			}
		} else {
			var selectors []string
			for key, entry := range entries {
				if !strings.HasPrefix(key, "\x00") && matches(key) {
					selectors = append(selectors, entry.physical)
				}
			}
			if len(selectors) == 0 {
				return nil, errors.New("routing balancer has no available members")
			}
			balancer.OutboundSelector = selectors
		}
		if balancer.FallbackTag != "" {
			if balancer.FallbackTag, err = resolve(balancer.FallbackTag); err != nil {
				return nil, err
			}
		}
	}
	return config, nil
}

// ApplyRouting accepts a complete generated configuration, not a path. Staged
// handlers are unreachable from the old rules; router replacement is the commit
// point. Stop, Start, validation and concurrent applies share lifecycleMu.
func ApplyRouting(configText string) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if coreServer == nil || routingState == nil {
		return errors.New("Xray is not running")
	}
	document, err := decodeRoutingDocument([]byte(configText))
	if err != nil {
		return err
	}
	state := routingState
	if !reflect.DeepEqual(staticRoutingDocument(state.config), staticRoutingDocument(document)) {
		return errors.New("live routing cannot change non-routing settings or inbound transport; restart required")
	}
	oldRouting := object(state.config["routing"])
	if stringValue(oldRouting["script"]) != "" {
		return errors.New("live rules are unavailable while a routing script is active")
	}
	accounts, err := prepareAccounts(coreServer, state.config, document)
	if err != nil {
		return err
	}
	if reflect.DeepEqual(state.config, document) {
		return nil
	}
	if state.outbounds == nil {
		return errors.New("initial outbounds do not support live routing")
	}
	ordered, err := outboundDocuments(document)
	if err != nil {
		return err
	}
	entries := make(map[string]*liveOutbound, len(ordered))
	for _, entry := range ordered {
		if strings.HasPrefix(entry.key, state.prefix) {
			return errors.New("reserved live outbound namespace")
		}
		entries[entry.key] = entry
	}
	changed := make(map[string]bool, len(entries))
	visiting := make(map[string]bool, len(entries))
	visited := make(map[string]bool, len(entries))
	var inspect func(string) error
	var dependencyOrder []*liveOutbound
	inspect = func(key string) error {
		if visited[key] {
			return nil
		}
		if visiting[key] {
			return errors.New("cyclic outbound dialerProxy references")
		}
		entry := entries[key]
		if entry == nil {
			return errors.New("outbound references an unavailable dialerProxy")
		}
		visiting[key] = true
		old := state.outbounds[key]
		changed[key] = old == nil || !reflect.DeepEqual(old.config, entry.config)
		if err := outboundDependencies(entry.config, func(dependency string) error {
			if err := inspect(dependency); err != nil {
				return err
			}
			changed[key] = changed[key] || changed[dependency]
			return nil
		}); err != nil {
			return err
		}
		visiting[key] = false
		visited[key] = true
		dependencyOrder = append(dependencyOrder, entry)
		return nil
	}
	newVersions := 0
	for i, entry := range ordered {
		if err := inspect(entry.key); err != nil {
			return err
		}
		old := state.outbounds[entry.key]
		if !changed[entry.key] {
			entry.original = old.original
			entry.version = old.version
		}
		if !changed[entry.key] && old.physical != "" {
			entry.physical, entry.handler = old.physical, old.handler
		} else {
			entry.physical = state.prefix + strconv.FormatUint(state.generation+1, 10) + "." + strconv.Itoa(i) + "."
			if changed[entry.key] {
				newVersions++
				entry.version = entry.physical
			}
		}
	}
	if newVersions > 0 && state.retained+newVersions > maxLiveOutboundVersions {
		return errors.New("live outbound retention limit reached; restart explicitly to release retired connections")
	}
	manager := coreServer.GetFeature(outbound.ManagerType()).(outbound.Manager)
	candidateRouter, err := liveRouterConfig(document, entries, state, manager)
	if err != nil {
		return err
	}
	health, healthPlan, err := prepareLiveHealth(coreServer, document, entries, state.health)
	if err != nil {
		return err
	}
	healthCommitted := false
	defer func() {
		if !healthCommitted && health != nil && health != state.health {
			health.close()
		}
	}()
	var staged []outbound.Handler
	rollback := func(cause error) error {
		for i := len(staged) - 1; i >= 0; i-- {
			handler := staged[i]
			cause = errors.Join(cause, manager.RemoveHandler(context.Background(), handler.Tag()), handler.Close())
		}
		return cause
	}
	for _, entry := range dependencyOrder {
		if entry.handler != nil {
			continue
		}
		if !changed[entry.key] {
			entry.handler = &outboundAlias{Handler: state.outbounds[entry.key].handler, tag: entry.physical}
		} else {
			encoded, err := json.Marshal(entry.config)
			if err != nil {
				return rollback(err)
			}
			var rewritten map[string]any
			if err := json.Unmarshal(encoded, &rewritten); err != nil {
				return rollback(err)
			}
			rewriteOutboundDependencies(rewritten, entries)
			rewritten["tag"] = entry.physical
			encoded, err = json.Marshal(rewritten)
			if err != nil {
				return rollback(err)
			}
			var source conf.OutboundDetourConfig
			if err := json.Unmarshal(encoded, &source); err != nil {
				return rollback(errors.New("invalid live outbound configuration"))
			}
			config, err := source.Build()
			if err != nil {
				return rollback(err)
			}
			raw, err := core.CreateObject(coreServer, config)
			if err != nil {
				return rollback(err)
			}
			entry.handler = &exactOutboundHandler{Handler: raw.(outbound.Handler)}
		}
		// AddHandler inserts before calling Start; include the failing handler
		// in rollback too. RemoveHandler does not Close it in this core.
		staged = append(staged, entry.handler)
		if err := manager.AddHandler(context.Background(), entry.handler); err != nil {
			return rollback(err)
		}
	}
	runtimeRouter, ok := coreServer.GetFeature(routing.RouterType()).(*router.Router)
	if !ok {
		return rollback(errors.New("running router does not support live replacement"))
	}
	// Populate staged sources and publish their router under the same observation
	// lock. No healthy rules-only update exposes an empty or stale candidate set.
	if health != nil {
		health.mu.Lock()
		health.publishPlanLocked(healthPlan, time.Now())
	}
	err = runtimeRouter.ReloadRulesWithCandidates(candidateRouter, entries[ordered[0].key].physical, healthPlan.managedCandidates())
	if health != nil {
		if err == nil {
			health.replaceLocked(healthPlan)
		}
		health.mu.Unlock()
	}
	if err != nil {
		return rollback(err)
	}
	// Account replacement cannot fail. No candidate credentials are published
	// until all outbound creation and atomic rule replacement have succeeded.
	for _, replacement := range accounts {
		replacement.server.ReplaceAccounts(replacement.accounts)
	}
	state.config, state.outbounds = document, entries
	state.generation++
	state.retained += newVersions
	previousHealth := state.health
	state.health = health
	if health != nil {
		health.start()
	}
	healthCommitted = true
	if previousHealth != nil && previousHealth != health {
		previousHealth.close()
	}
	return nil
}

// prepareLiveCoreConfig ensures even initially rule-free cores have the real
// mutable router, without changing any initial handler identity or selector.
func prepareLiveCoreConfig(config *core.Config, document map[string]any) error {
	if err := validateHealthDocument(document); err != nil {
		return err
	}
	for index, app := range config.App {
		if app.Type != serial.GetMessageType(&router.Config{}) {
			continue
		}
		if stringValue(document["_yaxcDefaultSocksUser"]) == "" {
			return nil
		}
		instance, err := app.GetInstance()
		if err != nil {
			return err
		}
		configuration := instance.(*router.Config)
		if err := inheritDefaultUserRules(configuration, document); err != nil {
			return err
		}
		config.App[index] = serial.ToTypedMessage(configuration)
		return nil
	}
	config.App = append(config.App, serial.ToTypedMessage(&router.Config{}))
	return nil
}

// The app explicitly identifies the tunnel's default account. Password sharing
// alone cannot establish identity: other SOCKS clients may use the same password.
func inheritDefaultUserRules(config *router.Config, document map[string]any) error {
	username := stringValue(document["_yaxcDefaultSocksUser"])
	if username == "" {
		return nil
	}
	var affected []*router.RoutingRule
	for _, rule := range config.Rule {
		for _, user := range rule.UserEmail {
			matches := user == username
			if strings.HasPrefix(user, "regexp:") && len(user) > 7 {
				pattern, err := regexp.Compile(user[7:])
				matches = err == nil && pattern.MatchString(username)
			}
			if matches {
				affected = append(affected, rule)
				break
			}
		}
	}
	if len(affected) == 0 {
		return nil
	}
	identities := make(map[string]bool)
	inbounds, _ := document["inbounds"].([]any)
	for _, value := range inbounds {
		inbound := object(value)
		if protocol := stringValue(inbound["protocol"]); protocol != "socks" && protocol != "mixed" {
			continue
		}
		accounts, err := socksAccounts(inbound)
		if err != nil {
			return err
		}
		password, exists := accounts[username]
		if !exists {
			continue
		}
		for user, candidatePassword := range accounts {
			if uidAccount(user) && candidatePassword == password {
				identities[user] = true
			}
		}
	}
	users := make([]string, 0, len(identities))
	for user := range identities {
		users = append(users, user)
	}
	sort.Strings(users)
	for _, rule := range affected {
		for _, user := range users {
			found := false
			for _, existing := range rule.UserEmail {
				if existing == user {
					found = true
					break
				}
			}
			if !found {
				rule.UserEmail = append(rule.UserEmail, user)
			}
		}
	}
	return nil
}
