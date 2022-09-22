(ns ^:no-doc datalevin.bits
  "Handle binary encoding, byte buffers, etc."
  (:require [datalevin.datom :as d]
            [datalevin.constants :as c]
            [datalevin.util :as u]
            [datalevin.sparselist :as sl]
            [clojure.string :as s]
            [taoensso.nippy :as nippy])
  (:import [java.util ArrayList Arrays UUID Date Base64]
           [java.util.regex Pattern]
           [java.math BigInteger BigDecimal]
           [java.io Writer DataInput DataOutput ObjectInput ObjectOutput]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.lang String Character]
           [org.roaringbitmap RoaringBitmap RoaringBitmapWriter]
           [datalevin.datom Datom]
           [datalevin.utl BitOps]))

(set! *unchecked-math* true)

;; bytes <-> text

(def hex [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \A \B \C \D \E \F])

(defn hexify-byte
  "Convert a byte to a hex pair"
  [b]
  (let [v (bit-and ^byte b 0xFF)]
    [(hex (bit-shift-right v 4)) (hex (bit-and v 0x0F))]))

(defn hexify
  "Convert bytes to hex string"
  [bs]
  (apply str (mapcat hexify-byte bs)))

(defn ^:no-doc unhexify-2c
  "Convert two hex characters to a byte"
  [c1 c2]
  (unchecked-byte
    (+ (bit-shift-left (Character/digit ^char c1 16) 4)
       (Character/digit ^char c2 16))))

(defn ^:no-doc unhexify
  "Convert hex string to byte sequence"
  [s]
  (map #(apply unhexify-2c %) (partition 2 s)))

(defn ^:no-doc hexify-string [^String s] (hexify (.getBytes s)))

(defn ^:no-doc unhexify-string [s] (String. (byte-array (unhexify s))))

(defn text-ba->str
  "Convert a byte array to string, the array is known to contain text data"
  [^bytes ba]
  (String. ba StandardCharsets/UTF_8))

(defmethod print-method (Class/forName "[B")
  [^bytes bs, ^Writer w]
  (.write w "#datalevin/bytes ")
  (.write w "\"")
  (.write w ^String (u/encode-base64 bs))
  (.write w "\""))

(defn ^bytes bytes-from-reader
  [s]
  (u/decode-base64 s))

(defmethod print-method Pattern
  [^Pattern p, ^Writer w]
  (.write w "#datalevin/regex ")
  (.write w "\"")
  (.write w ^String (s/escape (.toString p) {\\ "\\\\"}))
  (.write w "\""))

(defn ^Pattern regex-from-reader
  [s]
  (Pattern/compile s))

;; nippy

(defn serialize
  "Serialize data to bytes"
  [x]
  (nippy/fast-freeze x))

(defn deserialize
  "Deserialize from bytes. "
  [bs]
  (binding [nippy/*thaw-serializable-allowlist*
            (into nippy/*thaw-serializable-allowlist*
                  c/*data-serializable-classes*)]
    (nippy/fast-thaw bs)))

;; bitmap

(defn bitmap
  "Create a roaringbitmap. Expect a sorted integer collection"
  ([]
   (RoaringBitmap.))
  ([ints]
   (let [^RoaringBitmapWriter writer (-> (RoaringBitmapWriter/writer)
                                         (.initialCapacity (count ints))
                                         (.get))]
     (doseq [^int i ints] (.add writer i))
     (.get writer))))

(defn bitmap-size [^RoaringBitmap bm] (.getCardinality bm))

(defn bitmap-del
  "Delete an int from the bitmap"
  [^RoaringBitmap bm i]
  (.remove bm ^int i)
  bm)

(defn bitmap-add
  "Add an int from the bitmap"
  [^RoaringBitmap bm i]
  (.add bm ^int i)
  bm)

(defn- get-bitmap
  [^ByteBuffer bf]
  (let [bm (RoaringBitmap.)] (.deserialize bm bf) bm))

(defn- put-bitmap [^ByteBuffer bf ^RoaringBitmap x] (.serialize x bf))

;; byte buffer

(defn ^ByteBuffer allocate-buffer
  "Allocate JVM off-heap ByteBuffer in the Datalevin expected endian order"
  [size]
  (ByteBuffer/allocateDirect size))

(defn buffer-transfer
  "Transfer content from one bytebuffer to another"
  ([^ByteBuffer src ^ByteBuffer dst]
   (.put dst src))
  ([^ByteBuffer src ^ByteBuffer dst n]
   (dotimes [_ n] (.put dst (.get src)))))

(defn- get-long
  "Get a long from a ByteBuffer"
  [^ByteBuffer bb]
  (.getLong bb))

(defn get-int
  "Get an int from a ByteBuffer"
  [^ByteBuffer bb]
  (.getInt bb))

(defn get-short
  "Get a short from a ByteBuffer"
  [^ByteBuffer bb]
  (.getShort bb))

(defn get-short-array
  "Get a short array from a ByteBuffer"
  [^ByteBuffer bb]
  (let [len (.getInt bb)
        sa  (short-array len)]
    (dotimes [i len]
      (aset sa i (.getShort bb)))
    sa))

(defn- get-byte
  "Get a byte from a ByteBuffer"
  [^ByteBuffer bb]
  (.get bb))

(defn get-bytes
  "Copy content from a ByteBuffer to a byte array, useful for
   e.g. read txn result, as buffer content is gone when txn is done"
  ([^ByteBuffer bb]
   (let [n  (.remaining bb)
         ba (byte-array n)]
     (.get bb ba)
     ba))
  ([^ByteBuffer bb n]
   (let [ba (byte-array n)]
     (.get bb ba 0 n)
     ba)))

(defn- get-bytes-val
  [^ByteBuffer bf ^long post-v]
  (get-bytes bf (- (.remaining bf) post-v)))

(defn- get-data
  "Read data from a ByteBuffer"
  ([^ByteBuffer bb]
   (when-let [bs (get-bytes bb)]
     (deserialize bs)))
  ([^ByteBuffer bb n]
   (when-let [bs (get-bytes-val bb n)] (deserialize bs))))

(def ^:no-doc ^:const float-sign-idx 31)
(def ^:no-doc ^:const double-sign-idx 63)
(def ^:no-doc ^:const instant-sign-idx 63)

(defn- code-instant [^long x] (bit-flip x instant-sign-idx))

(defn- get-instant
  [^ByteBuffer bf]
  (Date. ^long (code-instant (.getLong bf))))

(defn- put-instant
  [^ByteBuffer bf x]
  (.putLong bf (code-instant
                 (if (inst? x)
                   (inst-ms x)
                   (u/raise "Expect an inst? value" {:value x})))))

(defn- decode-float
  [x]
  (if (bit-test x float-sign-idx)
    (Float/intBitsToFloat (BitOps/intFlip x float-sign-idx))
    (Float/intBitsToFloat (BitOps/intNot ^int x))))

(defn- get-float
  [^ByteBuffer bb]
  (decode-float (.getInt bb)))

(defn- decode-double
  [x]
  (if (bit-test x double-sign-idx)
    (Double/longBitsToDouble (bit-flip x double-sign-idx))
    (Double/longBitsToDouble (bit-not ^long x))))

(defn- get-double
  [^ByteBuffer bb]
  (decode-double (.getLong bb)))

(defn boolean-value
  "Datalevin's boolean value in byte"
  [b]
  (case (short b)
    2 true
    1 false
    (u/raise "Illegal value, expecting a Datalevin boolean value" {})))

(defn- get-boolean
  [^ByteBuffer bb]
  (boolean-value (get-byte bb)))

(defn- put-long
  [^ByteBuffer bb n]
  (.putLong bb ^long n))

(defn- encode-float
  [x]
  (assert (not (Float/isNaN x)) "Cannot index NaN")
  (let [x (if (= x -0.0) 0.0 x)]
    (if (neg? ^float x)
      (BitOps/intNot (Float/floatToIntBits x))
      (BitOps/intFlip (Float/floatToIntBits x) ^long float-sign-idx))))

(defn- put-float
  [^ByteBuffer bb x]
  (.putInt bb (encode-float (float x))))

(defn- encode-double
  [^double x]
  (assert (not (Double/isNaN x)) "Cannot index NaN")
  (let [x (if (= x -0.0) 0.0 x)]
    (if (neg? x)
      (bit-not (Double/doubleToLongBits x))
      (bit-flip (Double/doubleToLongBits x) double-sign-idx))))

(defn- put-double
  [^ByteBuffer bb x]
  (.putLong bb (encode-double (double x))))

(defn put-int
  [^ByteBuffer bb n]
  (.putInt bb ^int (int n)))

(defn put-short
  [^ByteBuffer bb n]
  (.putShort bb ^short (short n)))

(defn put-bytes
  "Put bytes into a bytebuffer"
  [^ByteBuffer bb ^bytes bs]
  (.put bb bs))

(defn- put-byte
  [^ByteBuffer bb b]
  (.put bb ^byte (unchecked-byte b)))
(type (byte 1))

(defn encode-bigint
  [^BigInteger x]
  (let [bs (.toByteArray x)
        n  (alength bs)]
    (assert (< n 128)
            "Does not support integer beyond the range of [-2^1015, 2^1015-1]")
    (let [bs1 (byte-array (inc n))]
      (aset bs1 0 (byte (if (= (.signum x) -1)
                          (- 127 n)
                          (bit-flip n 7))))
      (System/arraycopy bs 0 bs1 1 n)
      bs1)))

(defn- put-bigint
  [^ByteBuffer bb ^BigInteger x]
  (put-bytes bb (encode-bigint x)))

(defn ^BigInteger decode-bigint
  [^bytes bs]
  (BigInteger. ^bytes (Arrays/copyOfRange bs 1 (alength bs))))

(defn- get-bigint
  [^ByteBuffer bb]
  (let [^byte b   (get-byte bb)
        n         (if (bit-test b 7) (byte (bit-flip b 7)) (byte (- 127 b)))
        ^bytes bs (get-bytes bb n)]
    (BigInteger. bs)))

(defn- put-bigdec
  [^ByteBuffer bb ^BigDecimal x]
  (put-double bb (.doubleValue x))
  (put-bigint bb (.unscaledValue x))
  (put-byte bb c/separator)
  (put-int bb (.scale x)))

(defn- encode-bigdec
  [^BigDecimal x]
  (let [^BigInteger v (.unscaledValue x)
        bs            (.toByteArray v)
        n             (alength bs)]
    (assert (< n 128)
            "Unscaled value of big decimal cannot go beyond the range of
             [-2^1015, 2^1015-1]")
    (let [bf (ByteBuffer/allocate (+ n 14))]
      (put-bigdec bf x)
      (.array bf))))

(defn- get-bigdec
  [^ByteBuffer bb]
  (get-double bb)
  (let [^BigInteger value (get-bigint bb)
        _                 (get-byte bb)
        ^int scale        (get-int bb)]
    (BigDecimal. value scale)))

(defn- put-data
  [^ByteBuffer bb x]
  (put-bytes bb (serialize x)))

;; bytes

(defn type-size
  "Return the storage size (bytes) of the fixed length data type (a keyword).
  Useful when calling `open-dbi`. E.g. return 9 for `:long` (i.e. 8 bytes plus
  an 1 byte header). Return nil if the given data type has no fixed size."
  [type]
  (case type
    :int     4
    :long    9
    :id      8
    :float   5
    :double  9
    :byte    1
    :boolean 2
    :instant 9
    :uuid    17
    nil))

(defn measure-size
  "measure size of x in number of bytes"
  [x]
  (cond
    (bytes? x)         (inc (alength ^bytes x))
    (int? x)           8
    (instance? Byte x) 1
    :else              (alength ^bytes (serialize x))))

;; nippy

;; TODO PR to nippy to work with bf directly
;; https://github.com/ptaoussanis/nippy/issues/140

(nippy/extend-freeze RoaringBitmap :datalevin/bitmap
                     [^RoaringBitmap x ^DataOutput out]
                     (.serialize x out))

(nippy/extend-thaw :datalevin/bitmap
                   [^DataInput in]
                   (doto (RoaringBitmap.)
                     (.deserialize in)))

(defn- put-nippy
  [bf x]
  (put-bytes bf (nippy/freeze x)))

(defn- get-nippy
  [bb]
  (nippy/thaw (get-bytes bb)))

;; index

(defmacro wrap-extrema
  [v vmin vmax b]
  `(if (keyword? ~v)
     (condp = ~v
       c/v0   ~vmin
       c/vmax ~vmax
       (u/raise "Expect other data types, got keyword instead" ~v {}))
     ~b))

(defn- string-bytes
  [^String v]
  (wrap-extrema v c/min-bytes c/max-bytes (.getBytes v StandardCharsets/UTF_8)))

(defn- key-sym-bytes
  [x]
  (let [^bytes nmb (string-bytes (name x))
        nml        (alength nmb)]
    (if-let [ns (namespace x)]
      (let [^bytes nsb (string-bytes ns)
            nsl        (alength nsb)
            res        (byte-array (+ nsl nml 1))]
        (System/arraycopy nsb 0 res 0 nsl)
        (System/arraycopy c/separator-ba 0 res nsl 1)
        (System/arraycopy nmb 0 res (inc nsl) nml)
        res)
      (let [res (byte-array (inc nml))]
        (System/arraycopy c/separator-ba 0 res 0 1)
        (System/arraycopy nmb 0 res 1 nml)
        res))))

(defn- keyword-bytes
  [x]
  (condp = x
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (key-sym-bytes x)))

(defn- symbol-bytes
  [x]
  (wrap-extrema x c/min-bytes c/max-bytes (key-sym-bytes x)))

(defn- data-bytes
  [v]
  (condp = v
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (serialize v)))

(defn- bigint-bytes
  [v]
  (condp = v
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (encode-bigint v)))

(defn- bigdec-bytes
  [v]
  (condp = v
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (encode-bigdec v)))

(defn- val-bytes
  "Turn value into bytes according to :db/valueType"
  [v t]
  (case t
    :db.type/keyword (keyword-bytes v)
    :db.type/symbol  (symbol-bytes v)
    :db.type/string  (string-bytes v)
    :db.type/boolean nil
    :db.type/long    nil
    :db.type/double  nil
    :db.type/float   nil
    :db.type/ref     nil
    :db.type/instant nil
    :db.type/uuid    nil
    :db.type/bytes   (wrap-extrema v c/min-bytes c/max-bytes v)
    :db.type/bigint  (bigint-bytes v)
    :db.type/bigdec  (bigdec-bytes v)
    (data-bytes v)))

(defn- long-header
  [v]
  (if (keyword? v)
    (condp = v
      c/v0   c/type-long-neg
      c/vmax c/type-long-pos
      (u/raise "Illegal keyword value " v {}))
    (if (neg? ^long v)
      c/type-long-neg
      c/type-long-pos)))

(defn- val-header
  [v t]
  (case t
    :db.type/keyword c/type-keyword
    :db.type/symbol  c/type-symbol
    :db.type/string  c/type-string
    :db.type/boolean c/type-boolean
    :db.type/float   c/type-float
    :db.type/double  c/type-double
    :db.type/ref     c/type-ref
    :db.type/instant c/type-instant
    :db.type/uuid    c/type-uuid
    :db.type/bytes   c/type-bytes
    :db.type/bigint  c/type-bigint
    :db.type/bigdec  c/type-bigdec
    :db.type/long    (long-header v)
    nil))

(defprotocol IIndexable
  (g [this])
  (set-g [this x]))

(deftype Indexable [e a v f b
                    ^:volatile-mutable g]
  IIndexable
  (g [_] g)
  (set-g [_ x] (set! g x)))

(defn pr-indexable [^Indexable i]
  [(.-e i) (.-a i) (.-v i) (.-f i) (hexify (.-b i)) (g i)])

(defmethod print-method Indexable
  [^Indexable i, ^Writer w]
  (.write w "#datalevin/Indexable ")
  (.write w (pr-str (pr-indexable i))))

(defn indexable
  "Turn datom parts into a form that is suitable for putting in indices,
  where aid is the integer id of an attribute, vt is its :db/valueType,
  max-gt is the current max giant id"
  [eid aid val vt max-gt]
  (let [hdr (val-header val vt)]
    (if-let [vb (val-bytes val vt)]
      (let [bl   (alength ^bytes vb)
            cut? (> bl c/+val-bytes-wo-hdr+)
            bas  (if cut?
                   (Arrays/copyOf ^bytes vb c/+val-bytes-trunc+)
                   vb)
            gid  (if cut? max-gt c/normal)]
        (->Indexable eid aid val hdr bas gid))
      (->Indexable eid aid val hdr nil c/normal))))

(defn giant?
  [^Indexable i]
  (not= (g i) c/normal))

(defn- put-uuid
  [bf ^UUID val]
  (put-long bf (.getMostSignificantBits val))
  (put-long bf (.getLeastSignificantBits val)))

(defn- get-uuid
  [bf]
  (UUID. (get-long bf) (get-long bf)))

(defn- put-fixed
  [bf val hdr]
  (case (short hdr)
    -64 (put-long bf (wrap-extrema val Long/MIN_VALUE -1 val))
    -63 (put-long bf (wrap-extrema val 0 Long/MAX_VALUE val))
    -11 (put-float bf (wrap-extrema val Float/NEGATIVE_INFINITY
                                    Float/POSITIVE_INFINITY val))
    -10 (put-double bf (wrap-extrema val Double/NEGATIVE_INFINITY
                                     Double/POSITIVE_INFINITY val))
    -9  (put-long bf (code-instant
                       (wrap-extrema val Long/MIN_VALUE Long/MAX_VALUE
                                     (.getTime ^Date val))))
    -8  (put-long bf (wrap-extrema val c/e0 Long/MAX_VALUE val))
    -7  (put-uuid bf (wrap-extrema val c/min-uuid c/max-uuid val))
    -3  (put-byte bf (wrap-extrema val c/false-value c/true-value
                                   (if val c/true-value c/false-value)))))

(defn- put-avg
  [bf ^Indexable x]
  (put-int bf (.-a x))
  (when-let [hdr (.-f x)] (put-byte bf hdr))
  (if-let [bs (.-b x)]
    (do (put-bytes bf bs)
        (when (giant? x) (put-byte bf c/truncator)))
    (put-fixed bf (.-v x) (.-f x)))
  (put-byte bf c/separator)
  (put-long bf (g x)))

(defn- put-veg
  [bf ^Indexable x]
  (when-let [hdr (.-f x)] (put-byte bf hdr))
  (if-let [bs (.-b x)]
    (do (put-bytes bf bs)
        (when (giant? x) (put-byte bf c/truncator)))
    (put-fixed bf (.-v x) (.-f x)))
  (put-byte bf c/separator)
  (put-long bf (.-e x))
  (when-let [h (.-h x)] (put-int bf h)))

(defn- sep->slash
  [^bytes bs]
  (let [n-1 (dec (alength bs))]
    (loop [i 0]
      (cond
        (= (aget bs i) c/separator) (aset-byte bs i c/slash)
        (< i n-1)                   (recur (inc i)))))
  bs)

(defn- skip
  [^ByteBuffer bf ^long bytes]
  (.position bf (+ (.position bf) bytes)))

(defn- get-string
  ([^ByteBuffer bf]
   (String. ^bytes (get-bytes bf) StandardCharsets/UTF_8))
  ([^ByteBuffer bf ^long post-v]
   (String. ^bytes (get-bytes-val bf post-v) StandardCharsets/UTF_8)))

(defn- get-key-sym-str
  [^ByteBuffer bf ^long post-v]
  (let [^bytes ba (sep->slash (get-bytes-val bf post-v))
        s         (String. ^bytes ba StandardCharsets/UTF_8)]
    (if (= (aget ba 0) c/slash) (subs s 1) s)))

(defn- get-keyword
  [^ByteBuffer bf ^long post-v]
  (keyword (get-key-sym-str bf post-v)))

(defn- get-symbol
  [^ByteBuffer bf ^long post-v]
  (symbol (get-key-sym-str bf post-v)))

(defn get-value
  [^ByteBuffer bf post-v]
  (.mark bf)
  (case (short (get-byte bf))
    -64 (get-long bf)
    -63 (get-long bf)
    -15 (get-bigint bf)
    -14 (get-bigdec bf)
    -11 (get-float bf)
    -10 (get-double bf)
    -9  (get-instant bf)
    -8  (get-long bf)
    -7  (get-uuid bf)
    -6  (get-string bf post-v)
    -5  (get-keyword bf post-v)
    -4  (get-symbol bf post-v)
    -3  (get-boolean bf)
    -2  (get-bytes-val bf post-v)
    (do (.reset bf)
        (get-data bf post-v))))

(defprotocol IRetrieved
  (e [this])
  (set-e [this e])
  (a [this])
  (set-a [this a]))

(deftype Retrieved [^:volatile-mutable e
                    ^:volatile-mutable a
                    v
                    g]
  IRetrieved
  (e [_] e)
  (set-e [_ x] (set! e x))
  (a [_] a)
  (set-a [_ x] (set! a x)))

(defn pr-retrieved [^Retrieved r]
  [(e r) (a r) (.-v r) (.-g r)])

(defmethod print-method Retrieved
  [^Retrieved r, ^Writer w]
  (.write w "#datalevin/Retrieved ")
  (.write w (pr-str (pr-retrieved r))))

;; (def ^:no-doc ^:const overflown-key (->Retrieved c/e0 c/overflown c/overflown c/normal))

(defn- get-avg
  [bf]
  (let [a (get-int bf)
        v (get-value bf 9)
        _ (get-byte bf)
        g (get-long bf)]
    (->Retrieved nil a v g)))

(defn- get-veg
  [bf]
  (let [v (get-value bf 17)
        _ (get-byte bf)
        e (get-long bf)
        g (get-long bf)]
    (->Retrieved e nil v g)))

(defn- put-attr
  "NB. not going to do range query on attr names"
  [bf x]
  (let [^bytes a (-> x str (subs 1) string-bytes)
        al       (alength a)]
    (assert (<= al c/+max-key-size+)
            "Attribute cannot be longer than 511 bytes")
    (put-bytes bf a)))

(defn- get-attr
  [bf]
  (let [^bytes bs (get-bytes bf)]
    (keyword (String. bs StandardCharsets/UTF_8))))

(defn- raw-header
  [v t]
  (case t
    :keyword c/type-keyword
    :symbol  c/type-symbol
    :string  c/type-string
    :boolean c/type-boolean
    :float   c/type-float
    :double  c/type-double
    :instant c/type-instant
    :uuid    c/type-uuid
    :bytes   c/type-bytes
    :bigint  c/type-bigint
    :bigdec  c/type-bigdec
    :long    (long-header v)
    nil))

(defn- put-sparse-list
  [bf x]
  (sl/serialize x bf))

(defn- get-sparse-list
  [bf]
  (let [sl (sl/sparse-arraylist)]
    (sl/deserialize sl bf)
    sl))

(defn put-buffer
  ([bf x]
   (put-buffer bf x :data))
  ([^ByteBuffer bf x x-type]
   (case x-type
     :string         (do (put-byte bf (raw-header x :string))
                         (put-bytes
                           bf (.getBytes ^String x StandardCharsets/UTF_8)))
     :int            (put-int bf x)
     :bigint         (do (put-byte bf (raw-header x :bigint))
                         (put-bigint bf x))
     :bigdec         (do (put-byte bf (raw-header x :bigdec))
                         (put-bigdec bf x))
     :short          (put-short bf x)
     :int-int        (let [[i1 i2] x]
                       (put-int bf i1)
                       (put-int bf i2))
     :int-int-int    (let [[i1 i2 i3] x]
                       (put-int bf i1)
                       (put-int bf i2)
                       (put-int bf i3))
     :long-long      (let [[l1 l2] x]
                       (put-long bf l1)
                       (put-long bf l2))
     :sial           (put-sparse-list bf x)
     :bitmap         (put-bitmap bf x)
     :term-info      (let [[i1 i2 i3] x]
                       (put-int bf i1)
                       (.putFloat bf (float i2))
                       (put-sparse-list bf i3))
     :doc-info       (let [[i1 i2] x]
                       (put-short bf i1)
                       (put-data bf i2))
     :long           (do (put-byte bf (raw-header x :long))
                         (put-long bf x))
     :id             (put-long bf x)
     :short-id       (put-int bf x)
     :float          (do (put-byte bf (raw-header x :float))
                         (put-float bf x))
     :double         (do (put-byte bf (raw-header x :double))
                         (put-double bf x))
     :byte           (put-byte bf x)
     :bytes          (do (put-byte bf (raw-header x :bytes))
                         (put-bytes bf x))
     :keyword        (do (put-byte bf (raw-header x :keyword))
                         (put-bytes bf (key-sym-bytes x)))
     :symbol         (do (put-byte bf (raw-header x :symbol))
                         (put-bytes bf (key-sym-bytes x)))
     :boolean        (do (put-byte bf (raw-header x :boolean))
                         (put-byte bf (if x c/true-value c/false-value)))
     :instant        (do (put-byte bf (raw-header x :instant))
                         (put-instant bf x))
     :instant-pre-06 (do (put-byte bf (raw-header x :instant))
                         (.putLong bf (.getTime ^Date x)))
     :uuid           (do (put-byte bf (raw-header x :uuid))
                         (put-uuid bf x))
     :attr           (put-attr bf x)
     :datom          (put-nippy bf x)
     :avg            (put-avg bf x)
     :veg            (put-veg bf x)
     :raw            (put-bytes bf x)
     (put-data bf x))))

(defn read-buffer
  ([bf]
   (read-buffer bf :data))
  ([^ByteBuffer bf v-type]
   (case v-type
     :string         (do (skip bf Byte/BYTES) (get-string bf))
     :short          (get-short bf)
     :int            (get-int bf)
     :int-int        [(get-int bf) (get-int bf)]
     :int-int-int    [(get-int bf) (get-int bf) (get-int bf)]
     :long-long      [(get-long bf) (get-long bf)]
     :bigint         (do (get-byte bf) (get-bigint bf))
     :bigdec         (do (get-byte bf) (get-bigdec bf))
     :bitmap         (get-bitmap bf)
     :sial           (get-sparse-list bf)
     :term-info      [(get-int bf) (.getFloat bf) (get-sparse-list bf)]
     :doc-info       [(get-short bf) (get-data bf)]
     :long           (do (skip bf Byte/BYTES) (get-long bf))
     :id             (get-long bf)
     :short-id       (get-int bf)
     :float          (do (skip bf Byte/BYTES) (get-float bf))
     :double         (do (skip bf Byte/BYTES) (get-double bf))
     :byte           (get-byte bf)
     :bytes          (do (skip bf Byte/BYTES) (get-bytes bf))
     :keyword        (do (skip bf Byte/BYTES) (get-keyword bf 0))
     :symbol         (do (skip bf Byte/BYTES) (get-symbol bf 0))
     :boolean        (do (skip bf Byte/BYTES) (get-boolean bf))
     :instant        (do (skip bf Byte/BYTES) (get-instant bf))
     :instant-pre-06 (do (skip bf Byte/BYTES) (Date. (.getLong bf)))
     :uuid           (do (skip bf Byte/BYTES) (get-uuid bf))
     :attr           (get-attr bf)
     :datom          (get-nippy bf)
     :avg            (get-avg bf)
     :veg            (get-veg bf)
     :raw            (get-bytes bf)
     (get-data bf))))
;; => #'datalevin.bits/read-buffer

(defn valid-data?
  "validate data type"
  [x t]
  (case t
    (:db.type/string :string)   (string? x)
    :int                        (and (int? x)
                                     (<= Integer/MIN_VALUE x Integer/MAX_VALUE))
    (:db.type/bigint :bigint)   (and (instance? java.math.BigInteger x)
                                     (<= c/min-bigint x c/max-bigint))
    (:db.type/bigdec :bigdec)   (and (instance? java.math.BigDecimal x)
                                     (<= c/min-bigdec x c/max-bigdec))
    (:db.type/long :db.type/ref
                   :long)       (int? x)
    (:db.type/float :float)     (and (float? x)
                                     (<= Float/MIN_VALUE x Float/MAX_VALUE))
    (:db.type/double :double)   (float? x)
    :byte                       (or (instance? java.lang.Byte x)
                                    (and (int? x) (<= -128 x 127)))
    (:db.type/bytes :bytes)     (bytes? x)
    (:db.type/keyword :keyword) (keyword? x)
    (:db.type/symbol :symbol)   (symbol? x)
    (:db.type/boolean :boolean) (boolean? x)
    (:db.type/instant :instant) (inst? x)
    (:db.type/uuid :uuid)       (uuid? x)
    true))
