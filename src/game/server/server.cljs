(ns ^:figwheel-always game.server.server
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require
    [cljs.nodejs :as nodejs]
    [hiccups.runtime :as hiccupsrt]
    [com.stuartsierra.component :as component]
    ))

(defonce http (nodejs/require "http"))
(defonce io-lib (nodejs/require "socket.io"))

(defrecord InitServer
  [app config server io]
  component/Lifecycle
  (start [component]
    (if
      server
      component
      (let
        [server (.createServer http #((:app app) %1 %2))
         socket-path (get-in config [:server :socket-path])
         socket-ns (get-in config [:server :socket-ns])
         io (io-lib
              server
              #js
              {
               :path socket-path
               })
         io (-> io (.of socket-ns))
         port (get-in config [:server :port])
         ]
        (-> server (.listen port))
        (-> component
          (assoc :server server)
          (assoc :io io)))))
  (stop [component] component))

(defn new-server
  []
  (component/using
    (map->InitServer {})
    [:app :config]))
