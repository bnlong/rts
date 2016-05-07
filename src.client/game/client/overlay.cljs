(ns ^:figwheel-always game.client.overlay
  (:require
    [cljs.pprint :as pprint]
    [com.stuartsierra.component :as component]
    [jayq.core :as jayq :refer [$]]
    [promesa.core :as p]
    [cats.core :as m]
    [rum.core :as rum]
    [game.client.common :as common :refer [new-jsobj list-item data unique-id]]
    [game.client.math :as math]
    [game.client.selection :as selection]
    [game.client.engine :as engine]
    [sablono.core :as sablono :refer-macros [html]]
    [clojure.string :as string :refer [join]]
    [game.shared.state :as state :refer [with-simple-cause]]
    )
  (:require-macros
    [infix.macros :refer [infix]]
    [game.shared.macros :as macros :refer [defcom]])
  )

(defn on-render
  [component]
  (let
    [health-bars (:health-bars component)
     t (common/game-time)
     screen-boxes (selection/get-screen-boxes component)
     line-width 2]
    (-> health-bars .clear)
    (-> health-bars (.lineStyle line-width 0x000000 1))
    (doseq
      [[i box] (map-indexed vector screen-boxes)]
      (let
        [mesh (aget box "mesh")
         unit (engine/get-unit-for-mesh (:units component) mesh)
         bar-height 8
         bar-block-width 12
         min-blocks 4
         [x1 y1 x2 y2] box
         box-width (- x2 x1)
         bar-width (max (* (math/round (/ box-width bar-block-width)) bar-block-width) (* min-blocks bar-block-width))
         ; center bar on box horizontally
         x1 (infix (box-width - bar-width) / 2 + x1)
         height (- y2 y1)
         health (/ (:health unit) (:max-health (:model unit)))
         health-width (* health bar-width)
         remainder (rem health-width bar-block-width)
         last-block-opacity (/ remainder bar-block-width)
         health-width (- health-width remainder)
         y1 (- y1 bar-height)
         color
         (cond
           (< health 0.25)
           0xFF0000
           (< health 0.5)
           0xFFA500
           (< health 0.75)
           0xFFFF00
           :else
           0x00FF00)
         ]
        (-> health-bars (.beginFill color 1))
        (-> health-bars
          (.drawRect x1 y1 health-width bar-height))
        (-> health-bars .endFill)
        (-> health-bars (.beginFill color last-block-opacity))
        (-> health-bars
          (.drawRect (+ x1 health-width) y1 bar-block-width bar-height))
        (-> health-bars .endFill)
        (doseq
          [i (range bar-block-width (inc bar-width) bar-block-width)]
          (-> health-bars
            (.drawRect x1 y1 i bar-height)))))))

(defcom
  new-overlay
  [$overlay params units camera renderer]
  [pixi-renderer stage health-bars]
  (fn [component]
    (let
      [$container (:$page params)
       $overlay (data $overlay)
       running (atom true)
       view (aget $overlay 0)
       width (-> $overlay .width)
       height (-> $overlay .height)
       pixi-renderer 
       (or pixi-renderer
        (new js/PIXI.WebGLRenderer
             width
             height
             #js
             {
              :view view
              :antialias true
              :transparent true
              :autoResize true
              }))
       stage (new js/PIXI.Container)
       health-bars (new js/PIXI.Graphics)
       component
       (-> component
         (assoc :health-bars health-bars)
         (assoc :stage stage)
         (assoc :pixi-renderer pixi-renderer))]
      (-> stage (.addChild health-bars))
      component))
  (fn [component] component))