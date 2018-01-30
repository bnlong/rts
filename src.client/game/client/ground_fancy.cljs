(ns ^:figwheel-always game.client.ground-fancy
  (:require
    [cljs.pprint :as pprint]
    [com.stuartsierra.component :as component]
    [promesa.core :as p]
    [cats.core :as m]
    [game.client.math :as math :refer [floor isNaN]]
    [game.client.common :as common :refer [data]]
    [game.client.config :as config]
    [game.client.resources :as resources])
  (:require-macros
    [infix.macros :refer [infix]]
    [game.shared.macros :as macros :refer [defcom]]))

(defn get-ground-material
  [ground renderer]
  (let
    [
      mesh (:mesh ground)
      config (:config ground)
      texture-loader (new THREE.TextureLoader)
      material (new js/THREE.MeshStandardMaterial)
      terrain-shader (-> js/THREE.ShaderTerrain .-terrain)
      uniforms-terrain (-> js/THREE.UniformsUtils (.clone (-> terrain-shader .-uniforms)))
      normal-shader (-> js/THREE .-NormalMapShader)
      rx 256
      ry 256
      pars
      #js
        {
          :minFilter THREE.LinearFilter
          :magFilter THREE.LinearFilter
          :format THREE.RGBFormat}
      normal-map (new js/THREE.WebGLRenderTarget rx ry pars)
      _ (-> normal-map .-texture .-generateMipmaps (set! false))
      uniforms-normal (-> js/THREE.UniformsUtils (.clone (-> normal-shader .-uniforms)))
      _ (-> uniforms-normal .-height .-value (set! 0.05))
      _ (-> uniforms-normal .-resolution .-value (.set rx ry))
      _ (-> uniforms-normal .-heightMap .-value (set! (:data-texture ground)))
      _ (-> js/DEBUG .-uniforms (set! uniforms-normal))
      normal-material
      (new
        js/THREE.ShaderMaterial
        #js
        {
          :uniforms uniforms-normal
          :vertexShader (-> normal-shader .-vertexShader)
          :fragmentShader (-> normal-shader .-fragmentShader)
          :lights false
          :fog false})
      ;normal-material (new js/THREE.MeshLambertMaterial)
      screen-width (-> js/window .-innerWidth)
      screen-height (-> js/window .-innerHeight)
      plane (new js/THREE.PlaneBufferGeometry screen-width screen-height)
      quad-target (new js/THREE.Mesh plane normal-material)
      _ (-> quad-target .-position .-z (set! -500))
      scene-render-target (new js/THREE.Scene)
      camera-ortho (new js/THREE.OrthographicCamera (/ screen-width -2) (/ screen-width 2) (/ screen-height 2) (/ screen-height -2) -10000 10000)
      _ (-> camera-ortho .-position .-z (set! 100))
      _ (-> scene-render-target (.add camera-ortho))
      _ (-> scene-render-target (.add quad-target))
      _ (-> renderer (.render scene-render-target camera-ortho normal-map true))
      ;_ (-> uniforms-terrain .-tNormal .-value (set! (-> normal-map .-texture)))
      material
      (new
        js/THREE.ShaderMaterial
        #js
        {
          :uniforms uniforms-terrain
          :vertexShader (-> terrain-shader .-vertexShader)
          :fragmentShader (-> terrain-shader .-fragmentShader)
          :lights true
          :fog false})
      _ (-> material .-uniforms .-tNormal .-value (set! (-> normal-map .-texture)))
      _ (-> material .-uniforms .-uNormalScale .-value (set! 3.5))
      _ (-> material .-uniforms .-tDisplacement .-value (set! (:data-texture ground)))
      _ (-> material .-uniforms .-tDisplacement .-needsUpdate (set! true))
      _ (-> material .-uniforms .-diffuse .-value (.setHex 0xFFFFFF))
      _ (-> material .-uniforms .-specular .-value (.setHex 0xFFFFFF))
      _ (-> material .-uniforms .-shininess .-value (set! 30))
      max-elevation (get-in config [:terrain :max-elevation])
      _ (-> material .-uniforms .-uDisplacementScale .-value (set! max-elevation))
      _ (-> material .-uniforms .-uRepeatBase .-value (.set 1 1))
      _ (-> material .-uniforms .-uRepeatOverlay .-value (.set 24 24))
      _ (-> material .-uniforms .-enableDiffuse1 .-value (set! true))
      _ (-> material .-uniforms .-enableDiffuse2 .-value (set! true))
      _ (-> material .-uniforms .-needsUpdate (set! true))
      _ (-> material .-needsUpdate (set! true))
      wrapping (-> js/THREE .-RepeatWrapping)
      map-repeat-width 6
      map-repeat-height 6
      on-load (fn [texture]
               (-> texture .-wrapS (set! wrapping))
               (-> texture .-wrapT (set! wrapping))
               (-> texture .-repeat (.set map-repeat-width map-repeat-height))
               (-> material .-uniforms .-tDiffuse1 .-value (set! texture))
               (-> material .-needsUpdate (set! true)))
      _ (-> texture-loader (.load "models/images/grasslight-big.jpg" on-load))
      on-load-d2
      (fn [texture]
        (-> texture .-wrapS (set! wrapping))
        (-> texture .-wrapT (set! wrapping))
        (-> texture .-repeat (.set map-repeat-width map-repeat-height))
        (-> material .-uniforms .-tDiffuse2 .-value (set! texture))
        (-> material .-needsUpdate (set! true)))
      _ (-> texture-loader (.load "models/images/backgrounddetailed6.jpg" on-load-d2))
      on-load-detail
      (fn [texture]
        (-> texture .-wrapS (set! wrapping))
        (-> texture .-wrapT (set! wrapping))
        (-> texture .-repeat (.set map-repeat-width map-repeat-height))
        (-> material .-uniforms .-tDetail .-value (set! texture))
        (-> material .-needsUpdate (set! true)))
      _ (-> texture-loader (.load "models/images/grasslight-big-nm.jpg" on-load-detail))
      on-load-atlas
      (fn [texture]
        (-> texture .-wrapS (set! (-> js/THREE .-ClampToEdgeWrapping)))
        (-> texture .-wrapT (set! (-> js/THREE .-ClampToEdgeWrapping)))
        (-> texture .-repeat (.set 1 1))
        (-> material .-uniforms .-tAtlas .-value (set! texture))
        (-> material .-needsUpdate (set! true)))
      _ (-> texture-loader (.load "models/images/terrain_atlas.png" on-load-atlas))
      on-load-tilemap
      (fn [evt]
        (this-as
          this
          (let
            [data (-> this .-response)
             tilesX 100
             tilesY 100
             tileWidth 32
             tileHeight 32
             atlasWidth 1024
             atlasHeight 1024
             width (* tilesX tileWidth)
             height (* tilesY tileHeight)
             layers (-> data .-layers)
             layer (aget layers 1)
             length (* tilesX tilesY)
             rgba-size 4
             data-texture-buffer (new js/Float32Array (* length rgba-size))
             _
              (doseq
                [idx (range length)]
                (let
                  [
                   tileIndex (- (aget (-> layer .-data) idx) 1)
                   atlasTilesX (/ atlasWidth tileWidth)
                   atlasTilesY (/ atlasHeight tileHeight)
                   x (* (mod tileIndex atlasTilesY) (/ tileWidth atlasWidth))
                   y (* (math/floor (/ tileIndex atlasTilesY)) (/ tileHeight atlasHeight))
                   z (/ tileWidth atlasWidth)
                   w (/ tileWidth atlasHeight)]
                  (aset
                    data-texture-buffer
                    (+ 0 (* idx rgba-size))
                    x)
                  (aset
                    data-texture-buffer
                    (+ 1 (* idx rgba-size))
                    y)
                  (aset
                    data-texture-buffer
                    (+ 2 (* idx rgba-size))
                    z)
                  (aset
                    data-texture-buffer
                    (+ 3 (* idx rgba-size))
                    w)))
             data-texture
              (new js/THREE.DataTexture
                data-texture-buffer
                tilesX
                tilesY
                js/THREE.RGBAFormat
                js/THREE.FloatType)]
            (do
              (-> data-texture .-minFilter (set! js/THREE.LinearFilter))
              (-> data-texture .-magFilter (set! js/THREE.LinearFilter))
              (-> material .-uniforms .-tTileMap .-value (set! data-texture))
              (-> material .-uniforms .-tTileMap .-needsUpdate (set! true))
              (-> data-texture .-needsUpdate (set! true))
              (-> material .-needsUpdate (set! true))
              (-> js/console (.log this))))))
      _ (resources/load-json "models/maps/map.json" on-load-tilemap (fn []) (fn []))]
    material))
