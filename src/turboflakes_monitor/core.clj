(ns turboflakes-monitor.core
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
  (let [cmd ["curl" "-s" "-m" "15" api-endpoint]
        {:keys [exit out]} (apply shell/sh cmd)]
    (when (zero? exit)
      (try
        (json/read-str out :key-fn keyword)
        (catch Exception _ nil)))))

(defn scrape []
  (let [now (/ (System/currentTimeMillis) 1000)]
    (when (>= (- now @last-scrape) scrape-interval)
      (reset! last-scrape now)
      (let [data (curl-api)]
        (reset! metrics data)
        (when data
          (println "✅ Scraped:" (java.util.Date.) (keys data)))))))

(defn json-key-to-metric-name [k]
  (str "turboflakes_" (name k)))

(defn generate-metrics []
  (let [data @metrics
        scrape-duration (- (/ (System/currentTimeMillis) 1000) @last-scrape)
        uptime (/ (- (System/currentTimeMillis) start-time) 1000)
        lines (transient [])]

    ;; RAW JSON KEYS → METRICS (EXACT VALUES)
    (doseq [[k v] data]
      (let [metric-name (json-key-to-metric-name k)]
        (cond
          (string? v)
          (conj! lines (str metric-name "{endpoint=\"" api-endpoint "\"} \"" v "\"\n"))
          (number? v)
          (conj! lines (str metric-name "{endpoint=\"" api-endpoint "\"} " v "\n"))
          (true? v)
          (conj! lines (str metric-name "{endpoint=\"" api-endpoint "\"} 1\n"))
          (false? v)
          (conj! lines (str metric-name "{endpoint=\"" api-endpoint "\"} 0\n"))
          (vector? v)
          (conj! lines (str metric-name "{endpoint=\"" api-endpoint "\"} " (count v) "\n")))))

    ;; Scrape info
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
    (println "🚀 TurboFlakes Monitor (RAW JSON) on:" metrics-port)
    (println "📊 http://localhost:" metrics-port "/metrics")))

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
