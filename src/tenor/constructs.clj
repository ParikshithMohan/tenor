;; tenor/constructs.clj
;; Primitive components of music theory.

(ns tenor.constructs
  (:use [overtone.live]))


;; --- Notes --- ;;

(defn make-note [notation]
  "Take pitch letter + octave notation
  and generate an overtone note keyword."
  (let [notation-re #"^([A-G])([#b]?)([1-9])$"
        components (re-matches notation-re notation)]
    (if components
      (keyword (reduce str (rest components)))
      (throw (Exception. "Invalid notation")))))

;; --- Time Signature --- ;;

(defn time-signature [note-count & [sig]]
  "Decompose a measure into randomly spaced out beats,
  within the time signature's note count."
  (cond
    (> note-count 3)
    (let [beat (rand-nth [2 3 4])
          sig (conj (or sig '()) beat)]
      (time-signature (- note-count beat) sig))
    (and (<= note-count 3) (> note-count 0))
    (let [sig (conj (or sig '()) note-count)]
      (time-signature 0 sig))
    :else sig))

;; --- Beats --- ;;;

(defn segment-beat [note-count note-value
                    & {:keys [sparseness]
                       :or {sparseness 1}}]
  "Break a beat down into sixteenth-note positions and
  assign notes and rests to each position by iteration."
  (let [sixteenth-count (* (/ 16 note-value) note-count)
        notes (range 1 (inc sixteenth-count))
        distribution (cons true (repeat sparseness false))]
    (filter
      (fn [x] (or (= 1 x) (rand-nth distribution)))
      notes)))

;; --- Scales --- ;;;

(defn generate-random-scale []
  "Generate a random scale (Example - :E3 :Dorian)."
  (let [scale-type (rand-nth (keys SCALE))
        ninth-octave-count 131
        note-name (find-note-name (rand-int ninth-octave-count))]
    (scale note-name scale-type)))

;; --- Measures --- ;;;

(defn segment-measure [measure
                       & {:keys [running-count result note-value sparseness]
                          :or {running-count 0 result '()
                               note-value 16 sparseness 1}}]
  "Segment a measure into beats and further segment the beats,
  essentially breaking the measure down into sixteenth-notes."
  (if (empty? measure)
    result
    (let [result (concat result (map #(+ running-count %)
                                     (segment-beat (first measure)
                                                   note-value
                                                   :sparseness sparseness)))
          running-count (+ running-count (first measure))]
      (segment-measure (rest measure)
                       :running-count running-count
                       :result result
                       :note-value note-value
                       :sparseness sparseness))))

(defn string-measures [measures note-count
                       & {:keys [running-string running-count]
                          :or {running-string '() running-count 0}}]
  "String multiple measures together into a single piece."
  (if (empty? measures)
    running-string
    (let [running-string (concat
                           running-string
                           (map #(+ running-count %) (first measures)))
          running-count (+ running-count note-count)]
      (string-measures (rest measures)
                       note-count
                       :running-string running-string
                       :running-count running-count))))

;; --- Intervals --- ;;

(defn up-step [degree] (inc degree))
(defn down-step [degree] (dec degree))
(defn up-leap [degree] (+ (rand-nth [2 3 4 5]) degree))
(defn down-leap [degree] (- (rand-nth [2 3 4 5]) degree))
(defn up-octave [degree] (+ 7 degree))
(defn down-octave [degree] (- 7 degree))

(defn make-move [scale degree]
  (let [movements '(up-step down-step up-leap down-leap up-octave down-octave)
        current-move (weighted-choose movements '(0.38 0.38 0.08 0.08 0.04 0.04))
        temp-degree ((resolve current-move) degree)]
    (if (and (> temp-degree 0) (>= (count scale) temp-degree))
      temp-degree
      (make-move scale temp-degree))))

(defn construct-intervals [scale note-count
                           & {:keys [running-interval degree]
                              :or {running-interval '()
                                   degree 1}}]
  (if (<= note-count 0)
    running-interval
    (let [degree (make-move scale degree)
          running-interval (concat running-interval (list degree))
          note-count (dec note-count)]
      (construct-intervals scale
                           note-count
                           :degree degree
                           :running-interval running-interval))))

(defn generate-intervals [scale note-count]
  (let [mid-intervals (construct-intervals scale (- note-count 2))
        scale-count (count scale)]
    (concat '(1)
            mid-intervals
            (list (rand-nth `(1 ~scale-count))))))

(defn intervals->notes [intervals scale]
  (map #(nth scale (dec %)) intervals))

;; --- Musical piece --- ;;

(defn map-entity [entity intervals]
  "Create a list of hash-maps of positions as keys
  and musical notes as values."
  (map #(hash-map :pos %1, :note %2) entity intervals))

(defn generate-piece [measure-count note-count note-value sparseness]
  "Generate a musical piece with measure-count measures,
  each of time signature with note-count and note-value."
  (let [time-sig (time-signature note-count)
        segmented (segment-measure time-sig note-value sparseness)
        piece (repeat measure-count segmented)]
    (string-measures piece note-count)))

(defn generate-entity-map [measure-count note-count
                            & {:keys [note-value sparseness scale]
                               :or {note-value 16
                                    sparseness 1
                                    scale (generate-random-scale)}}]
  "Take measure count,note count and note value, and generate a
  measure map using map-entity."
  (let [piece (generate-piece measure-count note-count note-value sparseness)
        scale-intervals (intervals->notes
                          (generate-intervals scale (count piece)) scale)]
  (map-entity piece scale-intervals)))

;; --- Chords --- ;;

(defn chordify [entity-map]
  (filter (fn [x] (weighted-coin 0.2)) entity-map))

;; --- Playback --- ;;

(defmacro construct-note [time player entity]
  "Take time, instrument and note entity as arguments,
  and return overtone code that plays the note."
  `(list 'at ~time (list ~player ~entity)))

(defmacro construct-piece [entity-maps base-time player pivot-time]
  "Take an entity map and run construct-note on each
  note, creating a list of overtone playback code components."
  `(map #(construct-note (+ ~pivot-time (* (:pos %) ~base-time))
                         ~player (:note %))
        ~entity-maps))

(defn play-piece [entity-maps base-time player
                    & {:keys [pivot-time] :or {pivot-time (now)}}]
  "Play the constructed piece with current time as start time."
  `(let [time# ~pivot-time]
     ~@(construct-piece entity-maps base-time player pivot-time)))

;; --- Multiple voices --- ;;

(defn generate-parallel-voices [& body]
  (pmap #(eval %) `(list ~@body)))

