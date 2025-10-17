(ns turboflakes-monitor.core
  (:require [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors TimeUnit])
  (:gen-class))

;; Configuration (will be set from CLI args)
(def api-endpoint (atom nil))
(def metrics-port (atom 8090))
(def scrape-interval (atom 10))

(def metrics (atom {}))
(def last-scrape (atom 0))
(def start-time (System/currentTimeMillis))

(defn curl-api []
  (let [cmd ["curl" "-s" "-m" "15" @api-endpoint]
        {:keys [exit out]} (apply shell/sh cmd)]
    (when (zero? exit)
      (try
        (json/read-str out :key-fn keyword)
        (catch Exception _ nil)))))

(defn scrape []
  (let [now (/ (System/currentTimeMillis) 1000)]
    (when (>= (- now @last-scrape) @scrape-interval)
      (reset! last-scrape now)
      (let [data (curl-api)]
        (reset! metrics data)
        (when data
          (println "✅ Scraped:" (java.util.Date.) (keys data)))))))

(defn json-key-to-metric-name [k]
  (str "turboflakes_" (name k)))

(defn escape-label-value [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn grade-to-numeric [grade-str]
  (when (string? grade-str)
    (let [grade-str (str/upper-case (str/trim grade-str))
          has-plus (str/ends-with? grade-str "+")
          base-grade (if has-plus
                       (subs grade-str 0 (dec (count grade-str)))
                       grade-str)
          base-value (case base-grade
                       "F" 1.0
                       "E" 2.0
                       "D" 3.0
                       "C" 4.0
                       "B" 5.0
                       "A" 6.0
                       nil)]
      (when base-value
        (if has-plus
          (+ base-value 0.5)
          base-value)))))

(defn generate-metrics []
  (let [data @metrics
        scrape-duration (- (/ (System/currentTimeMillis) 1000) @last-scrape)
        uptime (/ (- (System/currentTimeMillis) start-time) 1000)
        lines (transient [])]

    ;; Extract string values as labels (except grade)
    (let [string-labels (into {}
                              (comp
                               (filter (fn [[k v]] (and (string? v)
                                                        (not= k :grade))))
                               (map (fn [[k v]] [(name k) (escape-label-value v)])))
                              data)
          base-labels (str "endpoint=\"" (escape-label-value @api-endpoint) "\"")
          all-labels (if (seq string-labels)
                       (str base-labels ","
                            (str/join ","
                                      (map (fn [[k v]]
                                             (str k "=\"" v "\""))
                                           string-labels)))
                       base-labels)]

      ;; RAW JSON KEYS → METRICS (NUMERIC VALUES ONLY)
      (doseq [[k v] data]
        (let [metric-name (json-key-to-metric-name k)]
          (cond
            (and (= k :grade) (string? v))
            (when-let [numeric-grade (grade-to-numeric v)]
              (conj! lines (str metric-name "{" all-labels ",grade_letter=\"" (escape-label-value v) "\"} " numeric-grade "\n")))

            (= k :address)
            nil  ; Skip address field entirely

            (number? v)
            (conj! lines (str metric-name "{" all-labels "} " v "\n"))

            (true? v)
            (conj! lines (str metric-name "{" all-labels "} 1\n"))

            (false? v)
            (conj! lines (str metric-name "{" all-labels "} 0\n"))

            (vector? v)
            (conj! lines (str metric-name "_count{" all-labels "} " (count v) "\n")))))

      ;; Scrape info
      (conj! lines (str "turboflakes_monitor_scrape_duration_seconds{" all-labels "} " (format "%.3f" (double scrape-duration)) "\n"))
      (conj! lines (str "turboflakes_monitor_uptime_seconds{" all-labels "} " (int uptime) "\n")))

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
  (let [server (HttpServer/create (InetSocketAddress. @metrics-port) 0)
        executor (Executors/newFixedThreadPool 10)]
    (.createContext server "/metrics" (reify HttpHandler (handle [_ exchange] (metrics-handler exchange))))
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (root-handler exchange))))
    (.setExecutor server executor)
    (.start server)
    (println "🚀 TurboFlakes Monitor (RAW JSON) on:" @metrics-port)
    (println "📊 http://localhost:" @metrics-port "/metrics")))

(defn background-scrape []
  (while true
    (scrape)
    (Thread/sleep (* @scrape-interval 1000))))

(defn parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [arg (first args)]
        (cond
          (or (= arg "--endpoint") (= arg "-e"))
          (recur (drop 2 args) (assoc opts :endpoint (second args)))

          (or (= arg "--port") (= arg "-p"))
          (recur (drop 2 args) (assoc opts :port (Integer/parseInt (second args))))

          (or (= arg "--interval") (= arg "-i"))
          (recur (drop 2 args) (assoc opts :interval (Integer/parseInt (second args))))

          (or (= arg "--help") (= arg "-h"))
          (recur (drop 1 args) (assoc opts :help true))

          :else
          (recur (drop 1 args) opts))))))

(defn print-usage []
  (println "Usage: turboflakes-monitor [OPTIONS]")
  (println "")
  (println "Required:")
  (println "  -e, --endpoint URL    API endpoint to scrape (required)")
  (println "")
  (println "Optional:")
  (println "  -p, --port PORT       Metrics server port (default: 8090)")
  (println "  -i, --interval SEC    Scrape interval in seconds (default: 10)")
  (println "  -h, --help            Show this help message"))

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (when-not (:endpoint opts)
      (println "Error: --endpoint is required")
      (println "")
      (print-usage)
      (System/exit 1))

    (reset! api-endpoint (:endpoint opts))
    (reset! metrics-port (or (:port opts) 8090))
    (reset! scrape-interval (or (:interval opts) 10))

    (println "Configuration:")
    (println "  Endpoint:" @api-endpoint)
    (println "  Port:" @metrics-port)
    (println "  Interval:" @scrape-interval "seconds")
    (println "")

    (let [scrape-thread (Thread. ^Runnable background-scrape)]
      (.setDaemon scrape-thread true)
      (.start scrape-thread)
      (start-server)
      (while true (Thread/sleep 1000)))))
