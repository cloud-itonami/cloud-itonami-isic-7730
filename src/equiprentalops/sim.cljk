(ns equiprentalops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean rental-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a maintenance-inspection-scheduling request and a
  fleet-restock coordination request (both auto-commit clean at phase
  3), then an equipment-safety-concern flag (ALWAYS escalates, at any
  phase -- approve, then commit), then HARD-hold scenarios: an
  unregistered unit, a unit registered but not yet verified, a proposal
  whose own `:effect` is not `:propose`, and a proposal that has
  drifted into the permanently-excluded
  equipment-safety-clearance-finalization scope."
  (:require [langgraph.graph :as g]
            [equiprentalops.advisor :as advisor]
            [equiprentalops.store :as store]
            [equiprentalops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "fleet-manager-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        manager-phase-1 {:actor-id "mgr-1" :actor-role :fleet-manager :phase 1}
        manager-phase-3 {:actor-id "mgr-1" :actor-role :fleet-manager :phase 3}
        actor (op/build db)]

    (println "== log-rental-record unit-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-rental-record :asset-id "unit-1"
                                  :patch {:renter "Acme Contracting" :checkout "2026-07-14" :days 3}} manager-phase-1)]
      (println r)
      (println "-- human fleet manager approves --")
      (println (approve! actor "t1")))

    (println "\n== log-rental-record unit-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-rental-record :asset-id "unit-1"
                                  :patch {:renter "Acme Contracting" :return "2026-07-17"}} manager-phase-3))

    (println "\n== schedule-maintenance-inspection unit-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-maintenance-inspection :asset-id "unit-1"
                                  :patch {:item "hydraulic hose post-return inspection" :urgency "routine"}} manager-phase-3))

    (println "\n== coordinate-fleet-restock unit-1 (phase 3, clean, under threshold -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-fleet-restock :asset-id "unit-1"
                                  :patch {:item "replacement bucket teeth" :quantity 6 :estimated-cost 300}} manager-phase-3))

    (println "\n== coordinate-fleet-restock unit-1 (phase 3, over cost threshold -- ALWAYS escalates) ==")
    (let [r (exec-op actor "t4b" {:op :coordinate-fleet-restock :asset-id "unit-1"
                                  :patch {:item "replacement mini excavator unit" :quantity 1 :estimated-cost 15000}} manager-phase-3)]
      (println r)
      (println "-- human fleet manager approves --")
      (println (approve! actor "t4b")))

    (println "\n== flag-equipment-safety-concern unit-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-equipment-safety-concern :asset-id "unit-1"
                                 :patch {:concern "hydraulic hose leak observed after last return" :confidence 0.92}} manager-phase-3)]
      (println r)
      (println "-- human fleet manager reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-rental-record unit-99 (unregistered unit -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-rental-record :asset-id "unit-99"
                                  :patch {:renter "unknown"}} manager-phase-3))

    (println "\n== log-rental-record unit-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-rental-record :asset-id "unit-3"
                                  :patch {:renter "unknown"}} manager-phase-3))

    (println "\n== schedule-maintenance-inspection unit-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-maintenance-inspection :asset-id "unit-1"
                                           :patch {:item "annual safety recertification"}} manager-phase-3)))

    (println "\n== log-rental-record unit-1, advisor drifts into re-rent-without-inspection scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-rental-record :asset-id "unit-1"
                                   :out-of-scope? true
                                   :patch {}} manager-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
