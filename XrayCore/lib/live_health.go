package lib

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/app/observatory"
	"github.com/xtls/xray-core/app/router"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
)

const liveHealthMetadata = "_yaxcLiveHealth"

type liveHealthOptions struct {
	interval          time.Duration
	timeout           time.Duration
	concurrency       int
	failureThreshold  int
	recoveryThreshold int
	staleAfter        time.Duration
}

var liveHealthDefaults = liveHealthOptions{
	interval: 10 * time.Second, timeout: 5 * time.Second, concurrency: 4,
	failureThreshold: 2, recoveryThreshold: 3, staleAfter: 35 * time.Second,
}

type healthMemberConfig struct {
	ProfileID   int64  `json:"profileId"`
	Tag         string `json:"tag"`
	Fingerprint string `json:"fingerprint"`
}

type healthGroupConfig struct {
	ID                string               `json:"id"`
	Strategy          string               `json:"strategy"`
	BalancerTag       string               `json:"balancerTag"`
	BlockTag          string               `json:"blockTag"`
	SelectedProfileID *int64               `json:"selectedProfileId"`
	Members           []healthMemberConfig `json:"members"`
}

type healthConfig struct {
	URL    string              `json:"url"`
	Groups []healthGroupConfig `json:"groups"`
}

type healthRecord struct {
	key, tag            string
	state               string
	failures, successes int
	recovering          bool
	checkedAt           time.Time
	delayMs             int64
	next                time.Time
	inFlight            bool
	cancel              context.CancelFunc
}

type healthCandidateSnapshot struct {
	tags       []string
	validUntil time.Time
}

type healthCandidates struct {
	snapshot atomic.Pointer[healthCandidateSnapshot]
}

func (c *healthCandidates) Candidates() []string {
	snapshot := c.snapshot.Load()
	if snapshot == nil || !time.Now().Before(snapshot.validUntil) {
		return nil
	}
	return snapshot.tags
}

type healthGroup struct {
	config     healthGroupConfig
	records    []*healthRecord
	tags       []string
	rejectTag  string
	candidates *healthCandidates
}

type healthPlan struct {
	url     string
	groups  []*healthGroup
	records map[string]*healthRecord
}

// The stock observers have immutable prefix selectors and legacy unknown/error
// fallback semantics. This managed lifetime reuses Observatory.Probe, but binds
// observations to immutable graph versions and publishes fail-closed candidates.
// It never obtains lifecycleMu, so Stop can cancel and join every worker before
// closing the core while retaining that lock.
type liveHealth struct {
	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	dispatcher routing.Dispatcher
	options    liveHealthOptions
	plan       *healthPlan
	changed    chan struct{}
	workers    sync.WaitGroup
	failed     bool
	started    bool
}

func parseHealthConfig(document map[string]any, entries map[string]*liveOutbound) (*healthConfig, error) {
	value, exists := document[liveHealthMetadata]
	if !exists {
		return nil, nil
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.DisallowUnknownFields()
	var config healthConfig
	if err := decoder.Decode(&config); err != nil {
		return nil, errors.New("invalid managed availability metadata")
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return nil, errors.New("invalid managed availability metadata")
	}
	probeURL, err := url.Parse(config.URL)
	if err != nil || (probeURL.Scheme != "http" && probeURL.Scheme != "https") || probeURL.Hostname() == "" || probeURL.User != nil || probeURL.Fragment != "" || len(config.Groups) == 0 {
		return nil, errors.New("managed availability requires an absolute HTTP(S) probe URL and groups")
	}
	balancers := make(map[string]map[string]any)
	values, _ := object(document["routing"])["balancers"].([]any)
	for _, value := range values {
		balancer := object(value)
		tag := stringValue(balancer["tag"])
		if _, exists := balancers[tag]; exists {
			return nil, errors.New("duplicate routing balancer")
		}
		balancers[tag] = balancer
	}
	groupIDs, balancerIDs := make(map[string]bool), make(map[string]bool)
	for _, group := range config.Groups {
		if group.ID == "" || groupIDs[group.ID] || group.BalancerTag == "" || balancerIDs[group.BalancerTag] || len(group.Members) == 0 {
			return nil, errors.New("invalid or duplicate managed availability group")
		}
		groupIDs[group.ID], balancerIDs[group.BalancerTag] = true, true
		if group.Strategy != "Select" && group.Strategy != "Random" && group.Strategy != "RoundRobin" {
			return nil, errors.New("unknown managed availability strategy")
		}
		block := entries[group.BlockTag]
		if block == nil || (stringValue(block.config["protocol"]) != "blackhole" && stringValue(block.config["protocol"]) != "block") {
			return nil, errors.New("managed availability requires an explicit blackhole outbound")
		}
		balancer := balancers[group.BalancerTag]
		selectors, ok := balancer["selector"].([]any)
		strategy := stringValue(object(balancer["strategy"])["type"])
		wanted := "random"
		if group.Strategy == "RoundRobin" {
			wanted = "roundrobin"
		}
		if balancer == nil || !ok || len(selectors) != 1 || stringValue(selectors[0]) != group.BlockTag || !strings.EqualFold(strategy, wanted) || stringValue(balancer["fallbackTag"]) != "" {
			return nil, errors.New("managed availability balancer must start blocked without an observer fallback")
		}
		ids, tags := make(map[int64]bool), make(map[string]bool)
		for _, member := range group.Members {
			fingerprint, hashErr := hex.DecodeString(member.Fingerprint)
			if member.ProfileID <= 0 || ids[member.ProfileID] || member.Tag == "" || member.Tag == group.BlockTag || tags[member.Tag] || entries[member.Tag] == nil || hashErr != nil || len(fingerprint) != 32 {
				return nil, errors.New("invalid or missing managed availability member")
			}
			ids[member.ProfileID], tags[member.Tag] = true, true
		}
		if group.SelectedProfileID != nil && (!ids[*group.SelectedProfileID] || (group.Strategy == "Select" && group.Members[0].ProfileID != *group.SelectedProfileID)) {
			return nil, errors.New("managed availability preferred member is missing or out of order")
		}
	}
	return &config, nil
}

func validateHealthDocument(document map[string]any) error {
	if _, exists := document[liveHealthMetadata]; !exists {
		return nil
	}
	ordered, err := outboundDocuments(document)
	if err != nil {
		return err
	}
	entries := make(map[string]*liveOutbound, len(ordered))
	for _, entry := range ordered {
		entries[entry.key] = entry
	}
	_, err = parseHealthConfig(document, entries)
	return err
}

func outboundRuntimeTag(entry *liveOutbound) string {
	if entry.physical != "" {
		return entry.physical
	}
	return entry.key
}

func prepareLiveHealth(server *core.Instance, document map[string]any, entries map[string]*liveOutbound, previous *liveHealth) (*liveHealth, *healthPlan, error) {
	config, err := parseHealthConfig(document, entries)
	if err != nil || config == nil {
		return nil, nil, err
	}
	monitor := previous
	if monitor == nil {
		dispatcher, ok := server.GetFeature(routing.DispatcherType()).(routing.Dispatcher)
		if !ok {
			return nil, nil, errors.New("managed availability requires the core dispatcher")
		}
		ctx, cancel := context.WithCancel(server.Context())
		monitor = &liveHealth{ctx: ctx, cancel: cancel, dispatcher: dispatcher, options: liveHealthDefaults, changed: make(chan struct{})}
	}
	monitor.mu.Lock()
	defer monitor.mu.Unlock()
	plan := &healthPlan{url: config.URL, records: make(map[string]*healthRecord)}
	for _, config := range config.Groups {
		group := &healthGroup{config: config, rejectTag: outboundRuntimeTag(entries[config.BlockTag]), candidates: &healthCandidates{}}
		for _, member := range config.Members {
			entry := entries[member.Tag]
			version := entry.version
			if version == "" {
				version = outboundRuntimeTag(entry)
			}
			key := plan.url + "\x00" + version
			record := plan.records[key]
			if record == nil && monitor.plan != nil && !monitor.failed {
				record = monitor.plan.records[key]
			}
			if record == nil {
				record = &healthRecord{key: key, tag: version, state: "checking"}
			}
			plan.records[key] = record
			group.records = append(group.records, record)
			group.tags = append(group.tags, outboundRuntimeTag(entry))
		}
		plan.groups = append(plan.groups, group)
	}
	return monitor, plan, nil
}

func (p *healthPlan) managedCandidates() map[string]router.ManagedBalancerCandidates {
	if p == nil {
		return nil
	}
	result := make(map[string]router.ManagedBalancerCandidates, len(p.groups))
	for _, group := range p.groups {
		result[group.config.BalancerTag] = router.ManagedBalancerCandidates{Source: group.candidates, RejectTag: group.rejectTag}
	}
	return result
}

func bindInitialHealth(server *core.Instance, config *core.Config, plan *healthPlan) error {
	runtimeRouter, ok := server.GetFeature(routing.RouterType()).(*router.Router)
	if !ok {
		return errors.New("managed availability requires the mutable router")
	}
	for _, app := range config.App {
		if app.Type != serial.GetMessageType(&router.Config{}) {
			continue
		}
		value, err := app.GetInstance()
		if err != nil {
			return err
		}
		return runtimeRouter.ReloadRulesWithCandidates(value.(*router.Config), "", plan.managedCandidates())
	}
	return errors.New("managed availability requires routing configuration")
}

func (h *liveHealth) signalLocked() {
	close(h.changed)
	h.changed = make(chan struct{})
}

func (h *liveHealth) replace(plan *healthPlan) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.replaceLocked(plan)
}

func (h *liveHealth) replaceLocked(plan *healthPlan) {
	if h.plan != nil {
		for key, record := range h.plan.records {
			if plan.records[key] != record && record.cancel != nil {
				record.cancel()
			}
		}
	}
	h.plan = plan
	h.failed = false
	h.publishLocked(time.Now())
	h.signalLocked()
}

func (h *liveHealth) publishLocked(now time.Time) {
	h.publishPlanLocked(h.plan, now)
}

func (h *liveHealth) publishPlanLocked(plan *healthPlan, now time.Time) {
	if plan == nil {
		return
	}
	for _, record := range plan.records {
		if record.state == "available" && !now.Before(record.checkedAt.Add(h.options.staleAfter)) {
			record.state, record.recovering, record.successes = "checking", true, 0
		}
	}
	for _, group := range plan.groups {
		previous := group.candidates.snapshot.Load()
		count := 0
		sameTags := previous != nil
		var expiry time.Time
		if !h.failed && h.ctx.Err() == nil {
			for i, record := range group.records {
				if record.state != "available" {
					continue
				}
				if previous == nil || count >= len(previous.tags) || previous.tags[count] != group.tags[i] {
					sameTags = false
				}
				count++
				deadline := record.checkedAt.Add(h.options.staleAfter)
				if expiry.IsZero() || deadline.Before(expiry) {
					expiry = deadline
				}
				if group.config.Strategy == "Select" {
					break
				}
			}
		}
		sameTags = sameTags && count == len(previous.tags)
		if sameTags && previous.validUntil.Equal(expiry) {
			continue
		}
		var tags []string
		if sameTags {
			tags = previous.tags
		} else if count != 0 {
			tags = make([]string, 0, count)
			for i, record := range group.records {
				if record.state == "available" {
					tags = append(tags, group.tags[i])
					if group.config.Strategy == "Select" {
						break
					}
				}
			}
		}
		group.candidates.snapshot.Store(&healthCandidateSnapshot{tags: tags, validUntil: expiry})
	}
}

func (h *liveHealth) start() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.started {
		return
	}
	h.started = true
	for range h.options.concurrency {
		h.workers.Add(1)
		go h.worker()
	}
}

func (h *liveHealth) close() {
	h.mu.Lock()
	h.cancel()
	h.failed = true
	h.publishLocked(time.Now())
	h.signalLocked()
	h.mu.Unlock()
	h.workers.Wait()
}

func (h *liveHealth) probe(ctx context.Context, tag, probeURL string) (result observatory.ProbeResult, failed bool) {
	defer func() {
		if recover() != nil {
			failed = true
		}
	}()
	return observatory.Probe(ctx, h.dispatcher, tag, probeURL, h.options.timeout), false
}

func (h *liveHealth) worker() {
	defer h.workers.Done()
	for {
		h.mu.Lock()
		if h.ctx.Err() != nil {
			h.mu.Unlock()
			return
		}
		now := time.Now()
		h.publishLocked(now)
		var selected *healthRecord
		var next time.Time
		if h.plan != nil && !h.failed {
			for _, record := range h.plan.records {
				if record.state == "available" {
					expiry := record.checkedAt.Add(h.options.staleAfter)
					if next.IsZero() || expiry.Before(next) {
						next = expiry
					}
				}
				if record.inFlight {
					continue
				}
				if !record.next.After(now) {
					if selected == nil || record.next.Before(selected.next) {
						selected = record
					}
				} else if next.IsZero() || record.next.Before(next) {
					next = record.next
				}
			}
		}
		if selected == nil {
			changed := h.changed
			h.mu.Unlock()
			if next.IsZero() {
				select {
				case <-h.ctx.Done():
					return
				case <-changed:
				}
			} else {
				timer := time.NewTimer(time.Until(next))
				select {
				case <-h.ctx.Done():
					timer.Stop()
					return
				case <-changed:
					timer.Stop()
				case <-timer.C:
				}
			}
			continue
		}
		probeCtx, cancel := context.WithCancel(h.ctx)
		selected.inFlight, selected.cancel = true, cancel
		selected.next = now.Add(h.options.interval)
		probeURL := h.plan.url
		h.mu.Unlock()
		result, failed := h.probe(probeCtx, selected.tag, probeURL)
		cancel()
		h.mu.Lock()
		selected.inFlight, selected.cancel = false, nil
		if h.ctx.Err() != nil {
			h.mu.Unlock()
			return
		}
		if h.plan != nil && h.plan.records[selected.key] == selected {
			if failed {
				h.failed = true
				h.publishLocked(time.Now())
				h.signalLocked()
				h.mu.Unlock()
				continue
			}
			selected.checkedAt, selected.delayMs = time.Now(), result.Delay
			if result.Alive {
				selected.failures = 0
				selected.successes++
				if selected.state == "available" || (!selected.recovering && selected.state == "checking") || selected.successes >= h.options.recoveryThreshold {
					selected.state, selected.recovering = "available", false
				}
			} else {
				selected.successes = 0
				selected.failures++
				selected.delayMs = 0
				if selected.failures >= h.options.failureThreshold {
					selected.state, selected.recovering = "unavailable", true
				}
			}
			h.publishLocked(time.Now())
		}
		h.signalLocked()
		h.mu.Unlock()
	}
}

type liveHealthMemberStatus struct {
	ProfileID     int64  `json:"profileId"`
	Fingerprint   string `json:"fingerprint"`
	State         string `json:"state"`
	LastCheckedAt int64  `json:"lastCheckedAt"`
	DelayMs       int64  `json:"delayMs"`
}

type liveHealthGroupStatus struct {
	ID                 string                   `json:"id"`
	Strategy           string                   `json:"strategy"`
	SelectedProfileID  *int64                   `json:"selectedProfileId"`
	State              string                   `json:"state"`
	ActiveProfileID    *int64                   `json:"activeProfileId"`
	EligibleProfileIDs []int64                  `json:"eligibleProfileIds"`
	Members            []liveHealthMemberStatus `json:"members"`
}

// LiveRoutingStatus contains only configured identities and observations; never
// endpoint/config text, authentication data or raw probe diagnostics.
func LiveRoutingStatus() string {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	status := struct {
		Running bool                    `json:"running"`
		Groups  []liveHealthGroupStatus `json:"groups"`
	}{Running: coreServer != nil, Groups: []liveHealthGroupStatus{}}
	if routingState != nil && routingState.health != nil {
		h := routingState.health
		h.mu.Lock()
		h.publishLocked(time.Now())
		if h.plan != nil {
			for _, group := range h.plan.groups {
				row := liveHealthGroupStatus{ID: group.config.ID, Strategy: group.config.Strategy, SelectedProfileID: group.config.SelectedProfileID, State: "unavailable", EligibleProfileIDs: []int64{}, Members: []liveHealthMemberStatus{}}
				checking := false
				for i, record := range group.records {
					member := group.config.Members[i]
					checkedAt := int64(0)
					if !record.checkedAt.IsZero() {
						checkedAt = record.checkedAt.UnixMilli()
					}
					row.Members = append(row.Members, liveHealthMemberStatus{ProfileID: member.ProfileID, Fingerprint: member.Fingerprint, State: record.state, LastCheckedAt: checkedAt, DelayMs: record.delayMs})
					checking = checking || record.state == "checking"
					if record.state == "available" && !h.failed {
						row.EligibleProfileIDs = append(row.EligibleProfileIDs, member.ProfileID)
						if row.ActiveProfileID == nil && group.config.Strategy == "Select" {
							id := member.ProfileID
							row.ActiveProfileID = &id
						}
					}
				}
				if h.failed {
					row.State = "error"
				} else if len(row.EligibleProfileIDs) != 0 {
					row.State = "available"
				} else if checking {
					row.State = "checking"
				}
				status.Groups = append(status.Groups, row)
			}
		}
		h.mu.Unlock()
	}
	// This concrete status contains only JSON-supported primitive fields.
	encoded, _ := json.Marshal(status)
	return string(encoded)
}
