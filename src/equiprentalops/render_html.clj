(ns equiprentalops.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (equiprentalops.operation ->
  equiprentalops.governor -> equiprentalops.store).
  No invented numbers, no timestamps, byte-identical across reruns."
  (:require [clojure.string :as str]
            [equiprentalops.store :as store]
            [equiprentalops.operation :as op]
            [equiprentalops.advisor :as advisor]
            [equiprentalops.governor :as governor]
            [equiprentalops.phase :as phase]
            [langgraph.graph :as g]))

(def ^:private operator {:actor-id "mgr-1" :actor-role :fleet-manager :phase phase/default-phase})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "fleet-manager-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real OperationActor StateGraph through a scenario built
  directly from store.cljc's real seed data (unit-1/unit-2/unit-3) and
  governor.cljc's real rules (verified sim.cljc first -- ran `clojure
  -M:dev:run` and cross-checked every id/op it uses against the real
  seed data and governor allowlist, plus its printed ledger/coordination
  -log output against governor.cljc's own check logic by hand; every
  outcome matched exactly, so it is trustworthy and this mirrors that
  proven scenario in a self-contained way rather than reusing sim.cljc's
  -main directly):

  unit-1 clears a rental-record log, a maintenance-inspection schedule,
  and an under-threshold fleet-restock, all at phase 3 (governor-clean +
  phase-auto-eligible -> auto-commit, no human in the loop). An
  over-threshold fleet-restock for unit-1 is ALWAYS-escalate
  (governor/high-cost-threshold) -> human approve! -> commit. A
  safety-concern flag for unit-1 is ALWAYS-escalate
  (governor/always-escalate-ops, at any phase, any confidence) -> human
  approve! -> commit.

  Three DISTINCT real HARD-hold reasons, all permanent and
  un-overridable by any human approval:
    - unit-99 (never registered in the store) hard-holds on
      :asset-unverified.
    - unit-1 routed through an advisor that claims a direct :effect
      :commit (instead of :propose) hard-holds on :effect-not-propose.
    - unit-1 routed through the out-of-scope? test hook
      (governor/scope-excluded-terms, a re-rent-without-inspection
      safety-clearance phrase) hard-holds on :scope-excluded.

  Returns the resulting db."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1" {:op :log-rental-record :asset-id "unit-1"
                        :patch {:renter "Acme Contracting" :checkout "2026-07-14" :days 3}})
    (exec! actor "t2" {:op :schedule-maintenance-inspection :asset-id "unit-1"
                        :patch {:item "hydraulic hose post-return inspection" :urgency "routine"}})
    (exec! actor "t3" {:op :coordinate-fleet-restock :asset-id "unit-1"
                        :patch {:item "replacement bucket teeth" :quantity 6 :estimated-cost 300}})
    (exec! actor "t4" {:op :coordinate-fleet-restock :asset-id "unit-1"
                        :patch {:item "replacement mini excavator unit" :quantity 1 :estimated-cost 15000}})
    (approve! actor "t4")
    (exec! actor "t5" {:op :flag-equipment-safety-concern :asset-id "unit-1"
                        :patch {:concern "hydraulic hose leak observed after last return" :confidence 0.92}})
    (approve! actor "t5")
    (exec! actor "t6" {:op :log-rental-record :asset-id "unit-99"
                        :patch {:renter "unknown"}})
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "t7" {:op :schedule-maintenance-inspection :asset-id "unit-1"
                                 :patch {:item "annual safety recertification"}}))
    (exec! actor "t8" {:op :log-rental-record :asset-id "unit-1"
                        :out-of-scope? true :patch {}})
    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for [ledger asset-id]
  (last (filter #(= asset-id (:asset-id %)) ledger)))

(defn- status-cell [fact]
  (cond
    (nil? fact)                       ["muted" "in progress"]
    (= :committed (:t fact))          ["ok" "committed"]
    (= :governor-hold (:t fact))      ["err" (str "governor-hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))  ["err" "approval-rejected"]
    :else                             ["muted" "in progress"]))

(defn- fleet-rows [db]
  (for [unit (store/all-assets db)
        :let [asset-id (:asset-id unit)
              [cls label] (status-cell (last-fact-for (store/ledger db) asset-id))]]
    (str "<tr><td><code>" (esc asset-id) "</code></td><td>" (esc (:name unit)) "</td>"
         "<td>" (if (:registered? unit) "yes" "no") "</td>"
         "<td>" (if (:verified? unit) "yes" "no") "</td>"
         "<td class=\"" cls "\">" (esc label) "</td></tr>")))

(defn- committed-rows [db]
  (for [{:keys [op asset-id value payload]} (store/coordination-log db)]
    (str "<tr><td><code>" (esc (name op)) "</code></td><td><code>" (esc asset-id) "</code></td>"
         "<td>" (esc (pr-str value)) "</td><td>" (esc (pr-str payload)) "</td></tr>")))

(defn- phase-rows []
  (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
    (str "<tr><td>" n "</td><td>" (esc label) "</td>"
         "<td>" (esc (str/join ", " (map name (sort writes)))) "</td>"
         "<td>" (esc (str/join ", " (map name (sort auto)))) "</td></tr>")))

(defn- ledger-rows [db]
  (for [{:keys [t op actor asset-id disposition basis confidence] :as fact} (store/ledger db)
        :let [[cls _] (status-cell fact)]]
    (str "<tr><td>" (esc (name t)) "</td><td><code>" (esc (name op)) "</code></td>"
         "<td>" (esc actor) "</td><td><code>" (esc asset-id) "</code></td>"
         "<td class=\"" cls "\">" (esc (name disposition)) "</td>"
         "<td>" (esc (str/join "," (map name basis))) "</td>"
         "<td>" (esc confidence) "</td></tr>")))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 1080px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; overflow-x: auto; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }
p.note { font-size: 12px; color: #666; }")

(defn render [db]
  (str
   "<!doctype html>\n<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>equiprentalops.render-html -- Equipment Rental Governor operator console</title>\n"
   "<style>\n" css "\n</style>\n</head>\n<body>\n"
   "<header class=\"bar\"><h1>EquipRentalOps -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 7730 &middot; equipment/machinery rental coordination &middot; phase "
   (esc phase/default-phase) "</span></header>\n<main>\n"

   "<section class=\"card\"><h2>Equipment Fleet (registered rental-asset directory)</h2>"
   "<table><thead><tr><th>Asset</th><th>Name</th><th>Registered</th><th>Verified</th><th>Latest status</th></tr></thead>"
   "<tbody>\n" (str/join "\n" (fleet-rows db)) "\n</tbody></table></section>\n"

   "<section class=\"card\"><h2>Committed Coordination Records</h2>"
   "<p class=\"note\">Every row here passed the EquipRentalGovernor (all three HARD checks clean) "
   "and, where the op is not phase-auto-eligible or is an always-escalate op, a human fleet-manager approval.</p>"
   "<table><thead><tr><th>Op</th><th>Asset</th><th>Value</th><th>Payload</th></tr></thead>"
   "<tbody>\n" (str/join "\n" (committed-rows db)) "\n</tbody></table></section>\n"

   "<section class=\"card\"><h2>Rollout Phase / Action Gate</h2>"
   "<p class=\"note\">Sourced from <code>equiprentalops.phase/phases</code> -- \"writes\" is the closed "
   "op allowlist enabled at that phase, \"auto\" is the subset eligible for governor-clean auto-commit. "
   "<code>:flag-equipment-safety-concern</code> is deliberately absent from every phase's auto set "
   "(governor/always-escalate-ops agrees independently). A <code>:coordinate-fleet-restock</code> proposal "
   "whose <code>:estimated-cost</code> exceeds " (esc governor/high-cost-threshold)
   " (USD) always escalates regardless of phase or confidence. Confidence floor: " (esc governor/confidence-floor) ".</p>"
   "<table><thead><tr><th>Phase</th><th>Label</th><th>Writes</th><th>Auto-commit</th></tr></thead>"
   "<tbody>\n" (str/join "\n" (phase-rows)) "\n</tbody></table></section>\n"

   "<section class=\"card\"><h2>Audit Ledger (append-only, verbatim)</h2>"
   "<table><thead><tr><th>Type</th><th>Op</th><th>Actor</th><th>Asset</th><th>Disposition</th><th>Basis</th><th>Confidence</th></tr></thead>"
   "<tbody>\n" (str/join "\n" (ledger-rows db)) "\n</tbody></table></section>\n"

   "</main>\n</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
