(ns turboflakes-monitor
  (:require [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors TimeUnit]))

;; Configuration
(def api-endpoint (or (System/getenv "API_ENDPOINT") "https://polkadot-onet-api.turboflakes.io/api/v1/validators/16A4n4UQqgxw5ndeehPjUAobDNmuX2bBoPXVKj4xTe16ktRN/grade"))
(def metrics-port (Integer/parseInt (or (System/getenv "METRICS_PORT") "8090")))
(def scrape-interval (Integer/parseInt (or (System/getenv "SCRAPE_INTERVAL") "10")))

(def metrics (atom {}))
(def last-scrape (atom 0))
(def start-time (System/currentTimeMillis))

(defn curl-api []
  "Fetch validator data via curl"
  (let [cmd ["curl" "-s" "-m" "15" api-endpoint]
        {:keys [exit out err]} (apply shell/sh cmd)]
    (println out)
    (if (zero? exit)
      (try
        (json/read-str out :key-fn keyword)
        (catch Exception e
          (println "💥 JSON parse error:" (.getMessage e))
          (println "📄 Raw:" (str/trim out))
          nil))
      (do
        (println "💥 Curl error:" err)
        nil))))

(defn collect-validator-data []
  "Extract metrics from REAL TurboFlakes API response"
  (let [response (curl-api)]
    (when (map? response)
      {:validator_address (:address response "")
       :grade (:grade response "N/A")
       :authority_inclusion (or (:authority_inclusion response) 0.0)
       :para_authority_inclusion (or (:para_authority_inclusion response) 0.0)
       :explicit_votes_total (or (:explicit_votes_total response) 0)
       :implicit_votes_total (or (:implicit_votes_total response) 0)
       :missed_votes_total (or (:missed_votes_total response) 0)
       :bitfields_availability_total (or (:bitfields_availability_total response) 0)
       :bitfields_unavailability_total (or (:bitfields_unavailability_total response) 0)
       :sessions_count (count (or (:sessions response) []))
       :api_timestamp (/ (System/currentTimeMillis) 1000)})))

(defn scrape []
  (let [now (/ (System/currentTimeMillis) 1000)]
    (when (>= (- now @last-scrape) scrape-interval)
      (reset! last-scrape now)
      (let [data (collect-validator-data)]
        (if data
          (do
            (reset! metrics data)
            (println "✅ Scraped:" (java.util.Date.)
                     "- Grade:" (:grade data)
                     "- Inclusion:" (:authority_inclusion data)
                     "- Votes:" (:explicit_votes_total data)))
          (println "❌ No data"))))))

(defn generate-metrics []
  (let [data @metrics
        scrape-duration (- (/ (System/currentTimeMillis) 1000) @last-scrape)
        uptime (/ (- (System/currentTimeMillis) start-time) 1000)
        lines (transient [])]

    ;; Address (label)
    (when-let [addr (:validator_address data)]
      (conj! lines (str "turboflakes_validator_address{endpoint=\"" api-endpoint "\"} \"" addr "\"\n")))

    ;; Grade (as label + numeric)
    (when-let [grade (:grade data)]
      (conj! lines (str "turboflakes_validator_grade{endpoint=\"" api-endpoint "\",grade=\"" grade "\"} 1\n")))

    ;; Numeric metrics
    (when-let [inc (:authority_inclusion data)]
      (conj! lines (str "turboflakes_authority_inclusion{endpoint=\"" api-endpoint "\"} " (* inc 100) "\n")))
    (when-let [para (:para_authority_inclusion data)]
      (conj! lines (str "turboflakes_para_inclusion{endpoint=\"" api-endpoint "\"} " (* para 100) "\n")))
    (when-let [exp (:explicit_votes_total data)]
      (conj! lines (str "turboflakes_explicit_votes_total{endpoint=\"" api-endpoint "\"} " exp "\n")))
    (when-let [imp (:implicit_votes_total data)]
      (conj! lines (str "turboflakes_implicit_votes_total{endpoint=\"" api-endpoint "\"} " imp "\n")))
    (when-let [miss (:missed_votes_total data)]
      (conj! lines (str "turboflakes_missed_votes_total{endpoint=\"" api-endpoint "\"} " miss "\n")))
    (when-let [avail (:bitfields_availability_total data)]
      (conj! lines (str "turboflakes_bitfields_availability{endpoint=\"" api-endpoint "\"} " avail "\n")))
    (when-let [unavail (:bitfields_unavailability_total data)]
      (conj! lines (str "turboflakes_bitfields_unavailability{endpoint=\"" api-endpoint "\"} " unavail "\n")))
    (when-let [sessions (:sessions_count data)]
      (conj! lines (str "turboflakes_sessions_count{endpoint=\"" api-endpoint "\"} " sessions "\n")))

    ;; Performance metrics
    (when (and (:explicit_votes_total data) (:missed_votes_total data))
      (let [total (+ (:explicit_votes_total data) (:missed_votes_total data))
            vote-success (if (zero? total) 100.0 (* 100.0 (/ (:explicit_votes_total data) total)))]
        (conj! lines (str "turboflakes_vote_success_percent{endpoint=\"" api-endpoint "\"} " vote-success "\n"))))

    (conj! lines (str "turboflakes_monitor_scrape_duration_seconds{endpoint=\"" api-endpoint "\"} " (format "%.3f" (double scrape-duration)) "\n"))
    (conj! lines (str "turboflakes_monitor_uptime_seconds{endpoint=\"" api-endpoint "\"} " (int uptime) "\n"))

    (str/join (persistent! lines))))

(defn metrics-handler [^HttpExchange exchange]
  (scrape)
  (let [response (generate-metrics)
        headers (.getResponseHeaders exchange)]
    (.add headers "Content-Type" "text/plain; version=0.0.4")
    (.sendResponseHeaders exchange 200 (count (.getBytes response)))
    (doto (.getResponseBody exchange)
      (.write (.getBytes response))
      (.close))))

(defn root-handler [^HttpExchange exchange]
  (.sendResponseHeaders exchange 404 0)
  (.close (.getResponseBody exchange)))

(defn start-server []
  (let [server (HttpServer/create (InetSocketAddress. metrics-port) 0)
        executor (Executors/newFixedThreadPool 10)]
    (.createContext server "/metrics" (reify HttpHandler (handle [_ exchange] (metrics-handler exchange))))
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (root-handler exchange))))
    (.setExecutor server executor)
    (.start server)
    (println "🚀 TurboFlakes Monitor running on:" metrics-port)
    (println "📊 Metrics: http://localhost:" metrics-port "/metrics")
    (println "🔗 API:" (subs api-endpoint 0 60) "...")
    (println "⏱️  Interval:" scrape-interval "s")))

(defn background-scrape []
  (while true
    (scrape)
    (Thread/sleep (* scrape-interval 1000))))

(defn -main [& args]
  (let [scrape-thread (Thread. ^Runnable background-scrape)]
    (.setDaemon scrape-thread true)
    (.start scrape-thread)
    (start-server)
    (while true (Thread/sleep 1000))))
