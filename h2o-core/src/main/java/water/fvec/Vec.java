package water.fvec;

import water.*;
import water.nbhm.NonBlockingHashMap;
import water.parser.BufferedString;
import water.util.*;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/** A distributed vector/array/column of uniform data.
 *
 *  <p>A distributed vector has a count of elements, an element-to-chunk
 *  mapping, a Java-like type (mostly determines rounding on store and
 *  display), and functions to directly load elements without further
 *  indirections.  The data is compressed, or backed by disk or both.
 *
 *  <p>A Vec is a collection of {@link Chunk}s, each of which holds between 1,000
 *  and 1,000,000 elements.  Operations on a Chunk are intended to be
 *  single-threaded; operations on a Vec are intended to be parallel and
 *  distributed on Chunk granularities, with each Chunk being manipulated by a
 *  separate CPU.  The standard Map/Reduce ({@link MRTask}) paradigm handles
 *  parallel and distributed Chunk access well.
 *
 *  <p>Individual elements can be directly accessed like a (very large and
 *  distributed) array - however this is not the fastest way to access the
 *  data.  Direct access from Chunks is faster, avoiding several layers of
 *  indirection.  In particular accessing a random row from the Vec will force
 *  the containing Chunk data to be cached locally (and network traffic to
 *  bring it local); accessing all rows from a single machine will force all
 *  the Big Data to be pulled local typically resulting in swapping and very
 *  poor performance.  The main API is provided for ease of small-data
 *  manipulations and is fairly slow for writing; when touching ALL the data
 *  you are much better off using e.g. {@link MRTask}.
 *
 *   <p>The main API is {@link #at}, {@link #set}, and {@link #isNA}:<br>
 *   <table class='table table-striped table-bordered' border="1" summary="">
 *   <tr><th>     Returns       </th><th>    Call      </th><th>  Missing?  </th><th>Notes</th>
 *   <tr><td>  {@code double}   </td><td>{@link #at}   </td><td>{@code NaN} </td><td></td>
 *   <tr><td>  {@code long}     </td><td>{@link #at8}  </td><td>   throws   </td><td></td>
 *   <tr><td>  {@code long}     </td><td>{@link #at16l}</td><td>   throws   </td><td>Low  half of 128-bit UUID</td>
 *   <tr><td>  {@code long}     </td><td>{@link #at16h}</td><td>   throws   </td><td>High half of 128-bit UUID</td>
 *   <tr><td>{@link BufferedString}</td><td>{@link #atStr}</td><td>{@code null}</td><td>Updates BufferedString in-place and returns it for flow-coding</td>
 *   <tr><td>  {@code boolean}  </td><td>{@link #isNA} </td><td>{@code true}</td><td></td>
 *   <tr><td>        </td><td>{@link #set(long,double)}</td><td>{@code NaN} </td><td></td>
 *   <tr><td>        </td><td>{@link #set(long,float)} </td><td>{@code NaN} </td><td>Limited precision takes less memory</td>
 *   <tr><td>        </td><td>{@link #set(long,long)}  </td><td>Cannot set  </td><td></td>
 *   <tr><td>        </td><td>{@link #set(long,String)}</td><td>{@code null}</td><td>Convenience wrapper for String</td>
 *   <tr><td>        </td><td>{@link #setNA(long)}     </td><td>            </td><td></td>
 *   </table>
 *
 *  <p>Example manipulating some individual elements:<pre>
 *    double r1 = vec.at(0x123456789L);  // Access element 0x1234567889 as a double
 *    double r2 = vec.at(-1);            // Throws AIOOBE
 *    long   r3 = vec.at8_abs(1);        // Element #1, as a long
 *    vec.set(2,r1+r3);                  // Set element #2, as a double
 *  </pre>
 *
 *  <p>Vecs have a loosely enforced <em>type</em>: one of numeric, {@link UUID}
 *  or {@link String}.  Numeric types are further broken down into integral
 *  ({@code long}) and real ({@code double}) types.  The {@code categorical} type is
 *  an integral type, with a String mapping side-array.  Most of the math
 *  algorithms will treat categoricals as small dense integers, and most categorical
 *  printouts will use the String mapping.  Time is another special integral
 *  type: it is represented as milliseconds since the unix epoch, and is mostly
 *  treated as an integral type when doing math but it has special time-based
 *  printout formatting.  All types support the notion of a missing element; for
 *  real types this is always NaN.  It is an error to attempt to fetch a
 *  missing integral type, and {@link #isNA} must be called first.  Integral
 *  types are losslessly compressed.  Real types may lose 1 or 2 ULPS due to
 *  compression.
 *
 *  <p>Reading elements as doubles, or checking for an element missing is
 *  always safe.  Reading a missing integral type throws an exception, since
 *  there is no NaN equivalent in the integer domain.  <em>Writing</em> to
 *  elements may throw if the backing data is read-only (file backed), and
 *  otherwise is fully supported.
 *
 *  <p>Note this dangerous scenario: loading a missing value as a double, and
 *  setting it as a long: <pre>
 *   set(row,(long)at(row)); // Danger!
 *</pre>
 *  The cast from a Double.NaN to a long produces a zero!  This code will
 *  silently replace a missing value with a zero.
 *
 *  <p>Vecs have a lazily computed {@link RollupStats} object and Key.  The
 *  RollupStats give fast access to the common metrics: {@link #min}, {@link
 *  #max}, {@link #mean}, {@link #sigma}, the count of missing elements ({@link
 *  #naCnt}) and non-zeros ({@link #nzCnt}), amongst other stats.  They are
 *  cleared if the Vec is modified and lazily recomputed after the modified Vec
 *  is closed.  Clearing the RollupStats cache is fairly expensive for
 *  individual {@link #set} calls but is easy to amortize over a large count of
 *  writes; i.e., batch writing is efficient.  This is normally handled by the
 *  MRTask framework; the {@link Vec.Writer} framework allows
 *  <em>single-threaded</em> efficient batch writing for smaller Vecs.
 *
 *  <p>Example usage of common stats:<pre>
 *    double mean = vec.mean();  // Vec's mean; first touch computes and caches rollups
 *    double min  = vec.min();   // Smallest element; already computed
 *    double max  = vec.max();   // Largest element; already computed
 *    double sigma= vec.sigma(); // Standard deviation; already computed
 *  </pre>
 *
 *  <p>Example: Impute (replace) missing values with the mean.  Note that the
 *  use of {@code vec.mean()} in the constructor uses (and computes) the
 *  general RollupStats before the MRTask starts.  Setting a value in the Chunk
 *  clears the RollupStats (since setting any value but the mean will change
 *  the mean); they will be recomputed at the next use after the MRTask.
 *  <pre>
 *    new MRTask{} { final double _mean = vec.mean();
 *      public void map( Chunk chk ) {
 *        for( int row=0; row &lt; chk._len; row++ )
 *          if( chk.isNA(row) ) chk.set(row,_mean);
 *      }
 *    }.doAll(vec);
 *  </pre>
 *
 *  <p>Vecs have a {@link Vec.VectorGroup}.  Vecs in the same VectorGroup have the
 *  same Chunk and row alignment - that is, Chunks with the same index are
 *  homed to the same Node and have the same count of rows-per-Chunk.  {@link
 *  Frame}s are only composed of Vecs of the same VectorGroup (or very small
 *  Vecs) guaranteeing that all elements of each row are homed to the same Node
 *  and set of Chunks - such that a simple {@code for} loop over a set of
 *  Chunks all operates locally.  See the example in the {@link Chunk} class.
 *
 *  <p>It is common and cheap to make new Vecs in the same VectorGroup as an
 *  existing Vec and initialized to e.g. zero.  Such Vecs are often used as
 *  temps, and usually immediately set to interest values in a later MRTask
 *  pass.
 * 
 *  <p>Example creation of temp Vecs:<pre>
 *    Vec tmp0 = vec.makeZero();         // New Vec with same VectorGroup and layout as vec, filled with zero
 *    Vec tmp1 = vec.makeCon(mean);      // Filled with 'mean'
 *    assert tmp1.at(0x123456789)==mean; // All elements filled with 'mean'
 *    for( int i=0; i&lt;100; i++ )         // A little math on the first 100 elements
 *      tmp0.set(i,tmp1.at(i)+17);       // ...set into the tmp0 vec
 *  </pre>
 *
 *  <p>Vec {@link Key}s have a special layout (enforced by the various Vec
 *  constructors) so there is a direct Key mapping from a Vec to a numbered
 *  Chunk and vice-versa.  This mapping is crucially used in all sorts of
 *  places, basically representing a global naming scheme across a Vec and the
 *  Chunks that make it up.  The basic layout created by {@link #newKey}:
 * <pre>
 *              byte:    0      1   2 3 4 5  6 7 8 9  10+
 *  Vec   Key layout: Key.VEC  -1   vec#grp    -1     normal Key bytes; often e.g. a function of original file name
 *  Chunk Key layout: Key.CHK  -1   vec#grp  chunk#   normal Key bytes; often e.g. a function of original file name
 *  RollupStats Key : Key.CHK  -1   vec#grp    -2     normal Key bytes; often e.g. a function of original file name
 *  Group Key layout: Key.GRP  -1     -1       -1     normal Key bytes; often e.g. a function of original file name
 *  ESPC  Key layout: Key.GRP  -1     -1       -2     normal Key bytes; often e.g. a function of original file name
 * </pre>
 *
 * @author Cliff Click
 */
public class Vec extends Keyed<Vec> {
  // Vec internal type: one of T_BAD, T_UUID, T_STR, T_NUM, T_CAT, T_TIME
  byte [] _types;                   // Vec Type

  /** Element-start per chunk, i.e. the row layout.  Defined in the
   *  VectorGroup.  This field is dead/ignored in subclasses that are
   *  guaranteed to have fixed-sized chunks such as file-backed Vecs. */
  public int _rowLayout;
  // Carefully set in the constructor and read_impl to be pointer-equals to a
  // common copy one-per-node.  These arrays can get both *very* common
  // (one-per-Vec at least, sometimes one-per-Chunk), and very large (one per
  // Chunk, could run into the millions).
  private transient long _espc[];

  // String domain, only for Categorical columns
  private String[][] _domains;

  protected Vec() {}


  /** Returns the categorical toString mapping array, or null if not an categorical column.
   *  Not a defensive clone (to expensive to clone; coding error to change the
   *  contents).
   *  @return the categorical / factor / categorical mapping array, or null if not a categorical column */
  public String[] domain(int c) { return _domains == null?null:_domains[c]; }
  public String[] domain() { return domain(0); }
  public String[][] allDomains(){return _domains;}
  /** Returns the {@code i}th factor for this categorical column.
   *  @return The {@code i}th factor */
  public final String factor( int c, int i ) { return domain(c)[i]; }
  /** Set the categorical/factor names.  No range-checking on the actual
   *  underlying numeric domain; user is responsible for maintaining a mapping
   *  which is coherent with the Vec contents. */
  public Vec setDomain(int c, String[] domain) {
    if(_domains == null)
      _domains = new String[numCols()][];
    _domains[c] = domain; if( domain != null ) _types[c] = T_CAT; return this;
  }
  /** Returns cardinality for categorical domain or -1 for other types. */
  public final int cardinality(int c) { return isCategorical(c) ? domain(c).length : -1; }
  public final int cardinality() { return cardinality(0); }

  // Vec internal type
  public static final byte T_BAD  =  0; // No none-NA rows (triple negative! all NAs or zero rows)
  public static final byte T_UUID =  1; // UUID
  public static final byte T_STR  =  2; // String
  public static final byte T_NUM  =  3; // Numeric, but not categorical or time
  public static final byte T_CAT  =  4; // Integer, with a categorical/factor String mapping
  public static final byte T_TIME =  5; // Long msec since the Unix Epoch - with a variety of display/parse options
  public static final String[] TYPE_STR=new String[] { "BAD", "UUID", "String", "Numeric", "Enum", "Time", "Time", "Time"};

  public static final boolean DO_HISTOGRAMS = true;

  /** True if this is an categorical column.  All categorical columns are also
   *  {@link #isInt}, but not vice-versa.
   *  @return true if this is an categorical column.  */
  public final boolean isCategorical() { return isCategorical(0);}
  public final boolean isCategorical(int c) {
//    assert (getType(c) ==T_CAT && _domains[c]!=null) || (getType(c) !=T_CAT && _domains[c]==null) || (getType(c) ==T_NUM && this instanceof InteractionWrappedVec && _domains[c]!=null);
    return getType(c) ==T_CAT;
  }

  public final double sparseRatio(int c) {
    return rollupStats().getRollups(c)._nzCnt/(double)length();
  }
  /** True if this is a UUID column.  
   *  @return true if this is a UUID column.  */
  public final boolean isUUID   (int c){ return getType(c) ==T_UUID; }
  public final boolean isUUID   (){ return isUUID(0); }
  /** True if this is a String column.  
   *  @return true if this is a String column.  */
  public final boolean isString (int c){ return getType(c) ==T_STR; }
  public final boolean isString (){ return isString(0); }
  /** True if this is a numeric column, excluding categorical and time types.
   *  @return true if this is a numeric column, excluding categorical and time types  */
  public final boolean isNumeric(int c){ return getType(c) ==T_NUM; }
  public final boolean isNumeric(){ return isNumeric(0); }
  /** True if this is a time column.  All time columns are also {@link #isInt}, but
   *  not vice-versa.
   *  @return true if this is a time column.  */
  public final boolean isTime   (int c){ return getType(c) ==T_TIME; }
  public final boolean isTime   (){ return isTime(0); }

  /** Build a numeric-type Vec; the caller understands Chunk layout (via the
   *  {@code espc} array).
   */
  public Vec( Key<Vec> key, int rowLayout, int numCols) { this(key, rowLayout, null, ArrayUtils.makeConst(numCols, T_NUM)); }

  /** Main default constructor; the caller understands Chunk layout (via the
   *  {@code espc} array), plus categorical/factor the {@code domain} (or null for
   *  non-categoricals), and the Vec type. */
  public Vec( Key<Vec> key, int rowLayout, String[][] domains, byte [] types ) {
    super(key);
    assert key._kb[0]==Key.VEC;
    _rowLayout = rowLayout;
    _types = types;
    _domains = domains;
    _espc = ESPC.espc(this);
  }

  public int vecId(){
    if(_key == null) return -1;
    return UnsafeUtils.get4(_key._kb,2);
  }
  public long[] espc() { if( _espc==null ) _espc = ESPC.espc(this); return _espc; }

  /** Number of elements in the vector; returned as a {@code long} instead of
   *  an {@code int} because Vecs support more than 2^32 elements. Overridden
   *  by subclasses that compute length in an alternative way, such as
   *  file-backed Vecs.
   *  @return Number of elements in the vector */
  public long length() {
    espc();
    return _espc[_espc.length-1];
  }

  /** Number of chunks, returned as an {@code int} - Chunk count is limited by
   *  the max size of a Java {@code long[]}.  Overridden by subclasses that
   *  compute chunks in an alternative way, such as file-backed Vecs.
   *  @return Number of chunks */
  public int nChunks() {
    return espc().length-1;
  }

  /** Convert a chunk-index into a starting row #.  For constant-sized chunks
   *  this is a little shift-and-add math.  For variable-sized chunks this is a
   *  table lookup. */
  long chunk2StartElem( int cidx ) { return espc()[cidx]; }

  /** Number of rows in chunk. Does not fetch chunk content. */
  private int chunkLen( int cidx ) { espc(); return (int) (_espc[cidx + 1] - _espc[cidx]); }

  /** Check that row-layouts are compatible. */
  boolean checkCompatible( Vec v ) {
    // Vecs are compatible iff they have same group and same espc (i.e. same length and same chunk-distribution)
    boolean res =  (espc() == v.espc() || Arrays.equals(_espc, v._espc)) &&
        (VectorGroup.sameGroup(this, v) || length() < 1e3);
    if(!res)
      System.out.println("haha");
    return (espc() == v.espc() || Arrays.equals(_espc, v._espc)) &&
            (VectorGroup.sameGroup(this, v) || length() < 1e3);
  }

  /** Default read/write behavior for Vecs.  File-backed Vecs are read-only. */
  boolean readable() { return true ; }
  /** Default read/write behavior for Vecs.  AppendableVecs are write-only. */
  boolean writable() { return true; }
  public void setBad(int c) { setType(c,T_BAD); }

  public byte getType(int c) {
    return _types[c];
  }

  protected byte setType(int c, byte t) {
    return _types[c] = t;
  }

  /** Get the column type. */
  public String get_type_str(int c) { return TYPE_STR[getType(c)]; }
  public String get_type_str() {
    String[] res = new String[_types.length];
    for (int i = 0; i < res.length; ++i)
      res[i] = get_type_str(i);
    return Arrays.toString(res);
  }
  public boolean isBinary(){return isBinary(0);}
  public boolean isBinary(int c){
    RollupStats rs = rollupStats().getRollups(c);
    return rs._isInt && rs._mins[0] >= 0 && rs._maxs[0] <= 1;
  }

  // ======= Create zero/constant Vecs ======
  /** Make a new zero-filled vec **/
  public static Vec makeZero( long len, boolean redistribute ) {
    return makeCon(0L,len,redistribute);
  }
  /** Make a new zero-filled vector with the given row count. 
   *  @return New zero-filled vector with the given row count. */
  public static Vec makeZero( long len ) { return makeCon(0d,len); }

  /** Make a new constant vector with the given row count, and redistribute the data
   * evenly around the cluster.
   * @param x The value with which to fill the Vec.
   * @param len Number of rows.
   * @return New cosntant vector with the given len.
   */
  public static Vec makeCon(double x, long len) {
    return makeCon(x,len,true);
  }

  /** Make a new constant vector with the given row count. 
   *  @return New constant vector with the given row count. */
  public static Vec makeCon(double x, long len, boolean redistribute) {
    int log_rows_per_chunk = FileVec.DFLT_LOG2_CHUNK_SIZE;
    return makeCon(x,len,log_rows_per_chunk,redistribute);
  }

  /** Make a new constant vector with the given row count, and redistribute the data evenly
   *  around the cluster.
   *  @return New constant vector with the given row count. */
  public static Vec makeCon(double x, long len, int log_rows_per_chunk) {
    return makeCon(x,len,log_rows_per_chunk,true);
  }

  /**
   * Make a new constant vector with minimal number of chunks. Used for importing SQL tables.
   *  @return New constant vector with the given row count. */
  public static Vec makeCon(long totSize, long len) {
    int safetyInflationFactor = 8;
    int nchunks = (int) Math.max(safetyInflationFactor * totSize / Value.MAX , 1);
    long[] espc = new long[nchunks+1];
    espc[0] = 0;
    for( int i=1; i<nchunks; i++ )
      espc[i] = espc[i-1]+len/nchunks;
    espc[nchunks] = len;
    VectorGroup vg = VectorGroup.VG_LEN1;
    return makeCon(0,vg,ESPC.rowLayout(vg._key,espc));
  }

  /** Make a new constant vector with the given row count.
   *  @return New constant vector with the given row count. */
  public static Vec makeCon(double x, long len, int log_rows_per_chunk, boolean redistribute) {
    int chunks0 = (int)Math.max(1,len>>log_rows_per_chunk); // redistribute = false
    int chunks1 = (int)Math.min( 4 * H2O.NUMCPUS * H2O.CLOUD.size(), len); // redistribute = true
    int nchunks = (redistribute && chunks0 < chunks1 && len > 10*chunks1) ? chunks1 : chunks0;
    long[] espc = new long[nchunks+1];
    espc[0] = 0;
    for( int i=1; i<nchunks; i++ )
      espc[i] = redistribute ? espc[i-1]+len/nchunks : ((long)i)<<log_rows_per_chunk;
    espc[nchunks] = len;
    VectorGroup vg = VectorGroup.VG_LEN1;
    return makeCon(x,vg,ESPC.rowLayout(vg._key,espc));
  }

  /** Make a new vector with the same size and data layout as the current one,
   *  and initialized to zero.
   *  @return A new vector with the same size and data layout as the current one,
   *  and initialized to zero.  */
  public Vec makeZero() { return makeCons(0); }

  /** A new vector with the same size and data layout as the current one, and
   *  initialized to zero, with the given categorical domain.
   *  @return A new vector with the same size and data layout as the current
   *  one, and initialized to zero, with the given categorical domain. */
  public Vec makeZero(String[] domain) {
    return makeCons(domain,0);
  }

  /** A new vector which is a copy of {@code this} one.
   *  @return a copy of the vector.  */
  public Vec makeCopy() { return makeCopy(_domains); }

  /** A new vector which is a copy of {@code this} one.
   *  @return a copy of the vector.  */
  public Vec makeCopy(String[][] domains){ return makeCopy(domains, _types); }

  public Vec makeCopy(String[][] domains, byte [] types) {
    Vec v = doCopy();
    v._types = types;
    v._domains = domains;
    DKV.put(v);
    return v;
  }

  public Vec doCopy() {
    final Vec v = new Vec(group().addVec(),_rowLayout,_domains,_types);
    new MRTask(){
      @Override public void map(ChunkAry c){
        DKV.put(v.chunkKey(c._cidx), c.copyIntoNewBlock(), _fs);
      }
    }.doAll(this);
    return v;
  }

  public static Vec makeVec(double [] vals, Key<Vec> vecKey){
    Vec v = new Vec(vecKey,ESPC.rowLayout(vecKey,new long[]{0,vals.length}),1);
    NewChunkAry nc = new NewChunkAry(v,0,new NewChunk[]{new NewChunk(Vec.T_NUM)},null);
    Futures fs = new Futures();
    for(double d:vals)
      nc.addNum(d);
    nc.close(fs);
    DKV.put(v._key, v, fs);
    fs.blockForPending();
    return v;
  }

  // allow missing (NaN) categorical values
  public static Vec makeVec(double [] vals, String [] domain, Key<Vec> vecKey){
    Vec v = new Vec(vecKey,ESPC.rowLayout(vecKey, new long[]{0, vals.length}), new String[][]{domain}, new byte[]{Vec.T_CAT});
    NewChunkAry nc = new NewChunkAry(v,0,new NewChunk[]{new NewChunk(Vec.T_NUM)},null);
    Futures fs = new Futures();
    for(double d:vals)
      nc.addInteger(d);
    nc.close(fs);
    DKV.put(v._key, v, fs);
    fs.blockForPending();
    return v;
  }
  // Warning: longs are lossily converted to doubles in nc.addNum(d)
  public static Vec makeVec(long [] vals, Key<Vec> vecKey){
    Vec v = new Vec(vecKey,ESPC.rowLayout(vecKey, new long[]{0, vals.length}),1);
    NewChunkAry nc = new NewChunkAry(v,0,new NewChunk[]{new NewChunk(Vec.T_NUM)},null);
    Futures fs = new Futures();
    for(long d:vals)
      nc.addInteger(d);
    nc.close(fs);
    DKV.put(v._key, v, fs);
    fs.blockForPending();
    return v;
  }

  public static Vec[] makeCons(double x, long len, int n) {
    Vec[] vecs = new Vec[n];
    for( int i=0; i<n; i++ )
      vecs[i] = makeCon(x,len,true);
    return vecs;
  }

  /** Make a new vector with the same size and data layout as the current one,
   *  and initialized to the given constant value.
   *  @return A new vector with the same size and data layout as the current one,
   *  and initialized to the given constant value.  */
  public Vec makeCon( final double d ) { return makeCon(d, group(), _rowLayout); }
  public Vec makeCons( final double... d ) { return makeCons(group(), _rowLayout,d); }
  public Vec makeCons( String [] domain, final double... d ) {
    Vec v = makeCons(d);
    v.setDomain(0,domain);
    DKV.put(v);
    return v;
  }

  private static Vec makeCons(VectorGroup group, int rowLayout, final double... d) {
    final Vec v0 = new Vec(group.addVec(), rowLayout, d.length);
    final int nchunks = v0.nChunks();
    new MRTask() {              // Body of all zero chunks
      @Override protected void setupLocal() {
        for( int i=0; i<nchunks; i++ ) {
          if(v0.isHomedLocally(i)) {
            Key k = v0.newChunkKey(i);
            Chunk[] cs = new Chunk[d.length];
            for(int j = 0; j < cs.length; ++j)
              cs[j] = new C0DChunk(d[j],0);
            if (k.home()) DKV.put(k, new DBlock.MultiChunkBlock(cs), _fs);
          }
        }
      }
    }.doAllNodes();
    DKV.put(v0._key, v0);        // Header last
    return v0;
  }
  private static Vec makeCon( final double d, VectorGroup group, int rowLayout ) {
    return makeCons(group,rowLayout,d);
  }

  public Vec makeZeros(int n){return makeZeros(n,null,null);}
  public Vec makeZeros(int n, String [][] domain, byte[] types){ return makeCons(_rowLayout,0,domain,types);}

  // Make a bunch of compatible zero Vectors
  public Vec makeCons(int n, final long l, String[][] domains, byte[] types) {
    final int nchunks = nChunks();
    Key<Vec> key = group().addVec();
    final Vec res = new Vec(key,_rowLayout,domains,types);
    new MRTask() {
      @Override protected void setupLocal() {
      for(int i = 0; i < nchunks; ++i) {
        if(res.isHomedLocally(i)){
          Key k = res.newChunkKey(i);
          Chunk [] cs = new Chunk[res.numCols()];
          for(int j = 0; j < cs.length; ++j)
            cs[j] = new C0LChunk(l, 0);
            DKV.put(k,new DBlock.MultiChunkBlock(cs), _fs);
        }
      }
      }
    }.doAllNodes();
    DKV.put(res);
    return res;
  }

  /** Make a new vector initialized to increasing integers, starting with 1.
   *  @return A new vector initialized to increasing integers, starting with 1. */
  public static Vec makeSeq( long len, boolean redistribute) {
    return new MRTask() {
      @Override public void map(ChunkAry cs) {
        for( int c = 0; c < cs._numCols; ++c )
          for(int r = 0; r < cs._len; r++ )
            cs.set(r,c, r + 1 + cs._start);
      }
    }.doAll(makeZero(len, redistribute))._fr.vecs();
  }

  /** Make a new vector initialized to increasing integers, starting with `min`.
   *  @return A new vector initialized to increasing integers, starting with `min`.
   */
  public static Vec makeSeq(final long min, long len) {
    return new MRTask() {
      @Override public void map(ChunkAry cs) {
        for (int c = 0; c < cs._numCols; ++c)
          for (int r = 0; r < cs._len; r++)
            cs.set(r,c, r + min + cs._start);
      }
    }.doAll(makeZero(len))._fr.vecs();
  }

  /** Make a new vector initialized to increasing integers, starting with `min`.
   *  @return A new vector initialized to increasing integers, starting with `min`.
   */
  public static Vec makeSeq(final long min, long len, boolean redistribute) {
    return new MRTask() {
      @Override public void map(ChunkAry cs) {
        for (int c = 0; c < cs._numCols; ++c)
          for (int r = 0; r < cs._len; r++)
            cs.set(r, c, r + min + cs._start);
      }
    }.doAll(makeZero(len, redistribute))._fr.vecs();
  }

  /** Make a new vector initialized to increasing integers mod {@code repeat}.
   *  @return A new vector initialized to increasing integers mod {@code repeat}.
   */
  public static Vec makeRepSeq( long len, final long repeat ) {
    return new MRTask() {
      @Override public void map(ChunkAry cs) {
        for(int c = 0; c < cs._len; ++c )
          for(int r = 0; r < cs._len; ++r )
            cs.set(r,c, (r + cs._start) % repeat);
      }
    }.doAll(makeZero(len))._fr.vecs();
  }

  /** Make a new vector initialized to random numbers with the given seed */
  public Vec makeRand( final long seed ) {
    Vec randVec = makeZero();
    new MRTask() {
      @Override public void map(ChunkAry c){
        Random rng = new RandomUtils.PCGRNG(c._start,1);
        for(int i = 0; i < c._len; ++i) {
          rng.setSeed(seed+c._start+i); // Determinstic per-row
          c.set(i, rng.nextFloat());
        }
      }
    }.doAll(randVec);
    return randVec;
  }

  // ======= Rollup Stats ======

  /** Vec's minimum value 
   *  @return Vec's minimum value */
  public double min() {return min(0);}
  public double min(int c)  { return mins(c)[0]; }
  /** Vec's 5 smallest values 
   *  @return Vec's 5 smallest values */
  public double [] mins(){return mins(0);}
  public double [] mins(int c){ return rollupStats().getRollups(c)._mins; }
  /** Vec's maximum value 
   *  @return Vec's maximum value */
  public double max()  { return max(0); }
  public double max(int c)  { return maxs(c)[0]; }
  /** Vec's 5 largest values 
   *  @return Vec's 5 largeest values */
  public double[] maxs() { return maxs(0); }
  public double[] maxs(int c){ return rollupStats().getRollups(c)._maxs; }
  /** True if the column contains only a constant value and it is not full of NAs 
   *  @return True if the column is constant */
  public final boolean isConst() { return isConst(0); }
  public final boolean isConst(int c) { return min(c) == max(c); }
  /** True if the column contains only NAs
   *  @return True if the column contains only NAs */
  public final boolean isBad() { return isBad(0); }
  public final boolean isBad(int c) { return naCnt(c)==length(); }
  /** Vecs's mean 
   *  @return Vec's mean */
  public double mean() {return mean(0);}
  public double mean(int c) {
    return rollupStats().getRollups(c)._mean;
  }
  /** Vecs's standard deviation
   *  @return Vec's standard deviation */
  public double sigma(){ return sigma(0); }
  public double sigma(int c){ return rollupStats().getRollups(c)._sigma; }
  /** Vecs's mode
   * @return Vec's mode */
  public int mode(){return mode(0);}
  public int mode(int c) {
    if (!isCategorical(c)) throw H2O.unimpl();
    long[] bins = bins(c);
    return ArrayUtils.maxIndex(bins);
  }
  /** Count of missing elements
   *  @return Count of missing elements */
  public long  naCnt() { return naCnt(0); }
  public long  naCnt(int c) { return rollupStats().getRollups(c)._naCnt; }
  /** Count of non-zero elements
   *  @return Count of non-zero elements */
  public long  nzCnt(int c) { return rollupStats().getRollups(c)._nzCnt; }
  public long  nzCnt() { return nzCnt(0); }
  /** Count of positive infinities
   *  @return Count of positive infinities */
  public long  pinfs(int c) { return rollupStats().getRollups(c)._pinfs; }
  public long  pinfs() { return pinfs(0); }
  /** Count of negative infinities
   *  @return Count of negative infinities */
  public long  ninfs(int c) { return rollupStats().getRollups(c)._ninfs; }
  public long  ninfs() { return ninfs(0); }
  /** <b>isInt</b> is a property of numeric Vecs and not a type; this
   *  property can be changed by assigning non-integer values into the Vec (or
   *  restored by overwriting non-integer values with integers).  This is a
   *  strong type for {@link #isCategorical} and {@link #isTime} Vecs.
   *  @return true if the Vec is all integers */
  public boolean isInt(int c) {return rollupStats().getRollups(c)._isInt; }
  public boolean isInt() {return isInt(0); }
  /** Size of compressed vector data. */
  public long byteSize(int c) {return rollupStats().getRollups(c)._size; }
  public long byteSize() {
    long s = 0;
    for(int i = 0; i < numCols(); ++i)
      s += byteSize(i);
    return s;
  }

  /** Default percentiles for approximate (single-pass) quantile computation (histogram-based). */
  public static final double PERCENTILES[] = {0.001,0.01,0.1,0.2,0.25,0.3,1.0/3.0,0.4,0.5,0.6,2.0/3.0,0.7,0.75,0.8,0.9,0.99,0.999};
  /** A simple and cheap histogram of the Vec, useful for getting a broad
   *  overview of the data.  Each bin is row-counts for the bin's range.  The
   *  bin's range is computed from {@link #base} and {@link #stride}.  The
   *  histogram is computed on first use and cached thereafter.
   *  @return A set of histogram bins, or null for String columns */
  public long[] bins(int c) { return rollupStats(true).getRollups(c)._bins;      }
  /** Optimistically return the histogram bins, or null if not computed 
   *  @return the histogram bins, or null if not computed */
  public long[] lazy_bins(int c) { return rollupStats(false).getRollups(c)._bins;      }
  /** The {@code base} for a simple and cheap histogram of the Vec, useful
   *  for getting a broad overview of the data.  This returns the base of
   *  {@code bins()[0]}.
   *  @return the base of {@code bins()[0]} */
  public double base(int c)      { return rollupStats().getRollups(c).h_base(); }
  /** The {@code stride} for a a simple and cheap histogram of the Vec, useful
   *  for getting a broad overview of the data.  This returns the stride
   *  between any two bins.
   *  @return the stride between any two bins */
  public double stride(int c)    { return rollupStats().getRollups(c).h_stride(); }

  /** A simple and cheap percentiles of the Vec, useful for getting a broad
   *  overview of the data.  The specific percentiles are take from {@link #PERCENTILES}. 
   *  @return A set of percentiles */
  public double[] pctiles(int c) { return rollupStats().getRollups(c)._pctiles;   }


  private static NonBlockingHashMap<Key,RPC> _pendingRollups = new NonBlockingHashMap<>();

  public byte[] get_types() {return _types;}

  public int homeNodeIdx(int i) {
    return chunkKey(i).home_node().index();
  }

  public String[][] domains() {return _domains;}

  public void setDomains(String[][] domains) {_domains = domains;}


  private static class RemoveColsFromRollupsTsk extends TAtomic<RollupsAry>{
    final int _ncols;
    final int [] _ids;
    boolean _removed;

    public RemoveColsFromRollupsTsk(int ncols, int [] ids){
      _ncols = ncols;
      _ids = ids;
    }
    @Override
    protected RollupsAry atomic(RollupsAry old) {
      if(old == null) old = new RollupsAry(_ncols);
      for(int c:_ids)
        old.markRemoved(c);
      _removed = (old.removedCnt() == _ncols);
      return old;
    }
  }

  public void removeCols(final int... ids){
    assert numCols() > 1;
    RemoveColsFromRollupsTsk t = new RemoveColsFromRollupsTsk(numCols(),ids);
    t.invoke(rollupStatsKey());
    if(t._removed) remove();
    else
    new MRTask(){
      @Override public void setupLocal(){
        for(int i = 0; i < nChunks(); ++i){
          Key k = chunkKey(i);
          if(k.home()){
            DBlock.MultiChunkBlock db = DKV.getGet(k);
            db.removeChunks(ids);
            DKV.put(k,db,_fs);
          }
        }
      }
    }.doAllNodes();
  }

  public RollupsAry tryFetchRollups(){
    // Fetch if present, but do not compute
    Value val = DKV.get(rollupStatsKey());
    if( val == null )           // No rollup stats present?
      return length() > 0 ? /*not computed*/null : /*empty vec*/new RollupsAry(0);
    RollupsAry rs = val.get();
    return rs;
  }

  /** Compute the roll-up stats as-needed */
  public RollupsAry rollupStats() { return rollupStats(false);}

  public Futures startRollupStats(Futures fs) { return startRollupStats(fs,false);}
  public Futures startRollupStats(Futures fs,boolean computeHisto) {
    RollupsAry rs = tryFetchRollups();
    if(rs == null || !rs.isReady(this,computeHisto))
      fs.add(new RPC(rollupStatsKey().home_node(),new RollupStats.ComputeRollupsTask(this,computeHisto)).addCompleter(new H2O.H2OCallback() {
        @Override public void callback(H2O.H2OCountedCompleter h2OCountedCompleter) {
          DKV.get(rollupStatsKey()); // fetch new results via DKV to enable caching of the results.
        }
      }).call());
    return fs;
  }

  public RollupsAry rollupStats(boolean computeHisto) {
    if( DKV.get(_key)== null ) throw new RuntimeException("Rollups not possible, because Vec was deleted: "+_key);
    final Key rskey = rollupStatsKey();
    RollupsAry rs = DKV.getGet(rskey);
    while(rs == null || (!rs.isReady(this,computeHisto))){
      // 1. compute only once
      try {
        RPC rpcNew = new RPC(rskey.home_node(),new RollupStats.ComputeRollupsTask(this, computeHisto));
        RPC rpcOld = _pendingRollups.putIfAbsent(rskey, rpcNew);
        if(rpcOld == null) {  // no prior pending task, need to send this one
          rpcNew.call().get();
          _pendingRollups.remove(rskey);
        } else // rollups computation is already in progress, wait for it to finish
          rpcOld.get();
      } catch( Throwable t ) {
        t.printStackTrace();
        System.err.println("Remote rollups failed with an exception, wrapping and rethrowing: "+t);
        throw new RuntimeException(t);
      }
      // 2. fetch - done in two steps to go through standard DKV.get and enable local caching
      rs = DKV.getGet(rskey);
    }
    return rs;
  }

  /** A high-quality 64-bit checksum of the Vec's content, useful for
   *  establishing dataset identity.
   *  @return Checksum of the Vec's content  */
  @Override protected long checksum_impl() {return rollupStats()._checksum;}


  private static class SetMutating extends TAtomic<RollupStats> {
    @Override protected RollupStats atomic(RollupStats rs) {
      return rs != null && rs.isMutating() ? null : RollupStats.makeMutating();
    }
  }

  /** Begin writing into this Vec.  Immediately clears all the rollup stats
   *  ({@link #min}, {@link #max}, {@link #mean}, etc) since such values are
   *  not meaningful while the Vec is being actively modified.  Can be called
   *  repeatedly.  Per-chunk row-counts will not be changing, just row
   *  contents. */
  public void preWriting( ) {
    if( !writable() ) throw new IllegalArgumentException("Vector not writable");
    final Key rskey = rollupStatsKey();
    Value val = DKV.get(rskey);
    if( val != null ) {
      RollupStats rs = val.get(RollupStats.class);
      if( rs.isMutating() ) return; // Vector already locked against rollups
    }
    // Set rollups to "vector isMutating" atomically.
    new SetMutating().invoke(rskey);
  }

  /** Stop writing into this Vec.  Rollup stats will again (lazily) be
   *  computed. */
  public Futures postWrite( Futures fs ) {
    // Get the latest rollups *directly* (do not compute them!).
    if (writable()) { // skip this for immutable vecs (like FileVec)
      final Key rskey = rollupStatsKey();
      Value val = DKV.get(rollupStatsKey());
      if (val != null) {
        RollupStats rs = val.get(RollupStats.class);
        if (rs.isMutating())  // Vector was mutating, is now allowed for rollups
          DKV.remove(rskey, fs);// Removing will cause them to be rebuilt, on demand
      }
    }
    return fs;                  // Flow-coding
  }




  // ======= Key and Chunk Management ======

  /** Convert a row# to a chunk#.  For constant-sized chunks this is a little
   *  shift-and-add math.  For variable-sized chunks this is a binary search,
   *  with a sane API (JDK has an insane API).  Overridden by subclasses that
   *  compute chunks in an alternative way, such as file-backed Vecs. */
   public int elem2ChunkIdx( long i ) {
    if( !(0 <= i && i < length()) ) throw new ArrayIndexOutOfBoundsException("0 <= "+i+" < "+length());
    long[] espc = espc();       // Preload
    int lo=0, hi = nChunks();
    while( lo < hi-1 ) {
      int mid = (hi+lo)>>>1;
      if( i < espc[mid] ) hi = mid;
      else                lo = mid;
    }
    while( espc[lo+1] == i ) lo++;
    return lo;
  }

  public int numCols(){return _types.length;}
  public long numRows(){return _espc[_espc.length-1];}



  /** Get a Vec Key from Chunk Key, without loading the Chunk.
   *  @return the Vec Key for the Chunk Key */
  public static Key getVecKey( Key chk_key ) {
    assert chk_key._kb[0]==Key.CHK;
    byte [] bits = chk_key._kb.clone();
    bits[0] = Key.VEC;
    UnsafeUtils.set4(bits, 6, -1); // chunk#
    return Key.make(bits);
  }



  /** Get a Chunk Key from a chunk-index.  Basically the index-to-key map.
   *  @return Chunk Key from a chunk-index */
  protected Key chunkKey(int cidx ) {
    return chunkKey(_key,cidx);
  }
  public static Key chunkKey(Key k, int cidx ) {
    byte [] bits = k._kb.clone();
    bits[0] = Key.CHK;
    UnsafeUtils.set4(bits, 6, cidx); // chunk#
    return Key.make(bits);
  }
  public boolean isHomedLocally(int cidx){return chunkKey(cidx).home_node() == H2O.SELF;}
  public Key newChunkKey(int cidx ) {return chunkKey(cidx);}



  // Filled in lazily and racily... but all writers write the exact identical Key
  public Key rollupStatsKey() {return chunkKey(-2);}

  /** Get a Chunk's Value by index.  Basically the index-to-key map, plus the
   *  {@code DKV.get()}.  Warning: this pulls the data locally; using this call
   *  on every Chunk index on the same node will probably trigger an OOM!  */
  public DBlock chunkIdx( int cidx ) {
    Value val = DKV.get(chunkKey(cidx));
    assert checkMissing(cidx,val) : "Missing chunk " + chunkKey(cidx);
    return val.get();
  }

  private boolean checkMissing(int cidx, Value val) {
    if( val != null ) return true;
    Log.err("Error: Missing chunk " + cidx + " for " + _key);
    return false;
  }

  /** Make a new random Key that fits the requirements for a Vec key. 
   *  @return A new random Vec Key */
  public static Key<Vec> newKey(){return newKey(Key.make());}

  /** Internally used to help build Vec and Chunk Keys; public to help
   *  PersistNFS build file mappings.  Not intended as a public field. */
  public static final int KEY_PREFIX_LEN = 4+4+1+1;
  /** Make a new Key that fits the requirements for a Vec key, based on the
   *  passed-in key.  Used to make Vecs that back over e.g. disk files. */
  static Key<Vec> newKey(Key k) {
    byte [] kb = k._kb;
    byte [] bits = MemoryManager.malloc1(kb.length + KEY_PREFIX_LEN);
    bits[0] = Key.VEC;
    bits[1] = -1;         // Not homed
    UnsafeUtils.set4(bits,2,0);   // new group, so we're the first vector
    UnsafeUtils.set4(bits,6,-1);  // 0xFFFFFFFF in the chunk# area
    System.arraycopy(kb, 0, bits, 4 + 4 + 1 + 1, kb.length);
    return Key.make(bits);
  }

  /** Make a ESPC-group key.  */
  private static Key espcKey(Key key) { 
    byte [] bits = key._kb.clone();
    bits[0] = Key.GRP;
    UnsafeUtils.set4(bits, 2, -1);
    UnsafeUtils.set4(bits, 6, -2);
    return Key.make(bits);
  }

  /** Make a Vector-group key.  */
  protected Key groupKey(){
    byte [] bits = _key._kb.clone();
    bits[0] = Key.GRP;
    UnsafeUtils.set4(bits, 2, -1);
    UnsafeUtils.set4(bits, 6, -1);
    return Key.make(bits);
  }

  Futures closeChunk(int cidx, int len, DBlock db, Futures fs){
    DKV.put(newChunkKey(cidx),db,fs);
    return fs;
  }

  Futures closeChunk(ChunkAry c, Futures fs){
    return closeChunk(c._cidx,c._len,c._numCols == 1?c.getChunk(0):new DBlock.MultiChunkBlock(c.getChunks()),fs);
  }




  /** Get the group this vector belongs to.  In case of a group with only one
   *  vector, the object actually does not exist in KV store.  This is the ONLY
   *  place VectorGroups are fetched.
   *  @return VectorGroup this vector belongs to */
  public final VectorGroup group() {
    Key gKey = groupKey();
    Value v = DKV.get(gKey);
    // if no group exists we have to create one
    return v==null ? new VectorGroup(gKey,1) : (VectorGroup)v.get();
  }

  /** The Chunk for a chunk#.  Warning: this pulls the data locally; using this
   *  call on every Chunk index on the same node will probably trigger an OOM!
   *  @return Chunk for a chunk# */
  public ChunkAry chunkForChunkIdx(int cidx) {
    DBlock data = chunkIdx(cidx);        // Chunk# to chunk data
    return data.chunkAry(this,cidx);
  }



  /** The Chunk for a row#.  Warning: this pulls the data locally; using this
   *  call on every Chunk index on the same node will probably trigger an OOM!
   *  @return Chunk for a row# */
  public final ChunkAry chunkForRow(long i) { return chunkForChunkIdx(elem2ChunkIdx(i)); }

  // ======= Direct Data Accessors ======


  /** Fetch element the slow way, as a long.  Floating point values are
   *  silently rounded to an integer.  Throws if the value is missing. 
   *  @return {@code i}th element as a long, or throw if missing */
  public final long  at8( long i) { return at8(i,0); }
  public final long  at8( long i, int j ) {
    ChunkAry ary = chunkForRow(i);
    return ary.at8(ary.chunkRelativeOffset(i),j);
  }

  public int at4(long i, int j) {
    ChunkAry ary = chunkForRow(i);
    return ary.at4(ary.chunkRelativeOffset(i),j);
  }

  /** Fetch element the slow way, as a double, or Double.NaN is missing.
   *  @return {@code i}th element as a double, or Double.NaN if missing */
  public final double at( long i) { return at(i,0); }
  public final double at( long i, int j ) {
    ChunkAry ary = chunkForRow(i);
    return ary.atd(ary.chunkRelativeOffset(i),j);
  }
  /** Fetch the missing-status the slow way. 
   *  @return the missing-status the slow way */
  public final boolean isNA(long i){ return isNA(i,0); }
  public final boolean isNA(long i, int j){
    ChunkAry ary = chunkForRow(i);
    return ary.isNA(ary.chunkRelativeOffset(i),j);
  }

  /** Fetch element the slow way, as the low half of a UUID.  Throws if the
   *  value is missing or not a UUID.
   *  @return {@code i}th element as a UUID low half, or throw if missing */
  public final long  at16l( long i) {return at16l(i,0);}
  public final long  at16l( long i , int j) {
    ChunkAry ary = chunkForRow(i);
    return ary.at16l(ary.chunkRelativeOffset(i),j);
  }
  /** Fetch element the slow way, as the high half of a UUID.  Throws if the
   *  value is missing or not a UUID.
   *  @return {@code i}th element as a UUID high half, or throw if missing */
  public final long  at16h( long i) {return at16h(i,0);}
  public final long  at16h( long i, int j ) {
    ChunkAry ary = chunkForRow(i);
    return ary.at16h(ary.chunkRelativeOffset(i),j);
  }

  /** Fetch element the slow way, as a {@link BufferedString} or null if missing.
   *  Throws if the value is not a String.  BufferedStrings are String-like
   *  objects than can be reused in-place, which is much more efficient than
   *  constructing Strings.
   *  @return {@code i}th element as {@link BufferedString} or null if missing, or
   *  throw if not a String */
  public final BufferedString atStr( BufferedString bStr, long i) {return atStr(bStr,i,0);}
  public final BufferedString atStr( BufferedString bStr, long i, int j ) {
    ChunkAry ck = chunkForRow(i);
    return ck.atStr(bStr, ck.chunkRelativeOffset(i),j);
  }

  /** A more efficient way to read randomly to a Vec - still single-threaded,
   *  but much faster than Vec.at(i).  Limited to single-threaded
   *  single-machine reads.
   *
   * Usage:
   * Vec.Reader vr = vec.new Reader();
   * x = vr.at(0);
   * y = vr.at(1);
   * z = vr.at(2);
   */
  public final class Reader {
    private ChunkAry _cache;
    private ChunkAry chk(long i) {
      ChunkAry c = _cache;
      return (c != null && c.start() <= i && i < c.start()+ c._len) ? c : (_cache = chunkForRow(i));
    }
    public final long    at8( long i) { return at8(i,0); }
    public final long    at8( long i, int j ) {
      ChunkAry ck = chk(i);
      return ck.at8(ck.chunkRelativeOffset(i),j);
    }
    public final double   at( long i) { return at(i,0);}
    public final double   at( long i, int j ) {
      ChunkAry ck = chk(i);
      return ck.atd(ck.chunkRelativeOffset(i),j);
    }
    public final boolean isNA(long i) {return isNA(i,0);}
    public final boolean isNA(long i, int j) {
      ChunkAry ck = chk(i);
      return ck.isNA(ck.chunkRelativeOffset(i),j);
    }
    public final long length() { return Vec.this.length(); }
  }

  /** Write element the slow way, as a long.  There is no way to write a
   *  missing value with this call.  Under rare circumstances this can throw:
   *  if the long does not fit in a double (value is larger magnitude than
   *  2^52), AND float values are stored in Vec.  In this case, there is no
   *  common compatible data representation.  */
  public final void set( long i, long l) {set(i,0,l);}
  public final void set( long i, int j, long l) {
    ChunkAry ck = chunkForRow(i);
    ck.set(ck.chunkRelativeOffset(i),j, l);
    postWrite(ck.close(new Futures())).blockForPending();
  }

  /** Write element the slow way, as a double.  Double.NaN will be treated as a
   *  set of a missing element. */
  public final void set( long i, double d) {set(i,0,d);}
  public final void set( long i, int j, double d) {
    ChunkAry ck = chunkForRow(i);
    ck.set(ck.chunkRelativeOffset(i), j, d);
    postWrite(ck.close(new Futures())).blockForPending();
  }

  /** Write element the slow way, as a float.  Float.NaN will be treated as a
   *  set of a missing element. */
  public final void set( long i, float  f) {set(i,(double)f);}

  final void setNA( long i) { setNA(i,0);}
  /** Set the element as missing the slow way.  */
  final void setNA( long i, int j ) {
    ChunkAry ck = chunkForRow(i);
    ck.setNA(ck.chunkRelativeOffset(i),j);
    postWrite(ck.close(new Futures())).blockForPending();
  }

  /** Write element the slow way, as a String.  {@code null} will be treated as a
   *  set of a missing element. */
  public final void set( long i, String str) {set(i,0,str);}
  public final void set( long i, int j, String str) {
    ChunkAry ck = chunkForRow(i);
    ck.set(ck.chunkRelativeOffset(i), j,str);
    postWrite(ck.close(new Futures())).blockForPending();
  }

  /** A more efficient way to write randomly to a Vec - still single-threaded,
   *  still slow, but much faster than Vec.set().  Limited to single-threaded
   *  single-machine writes.
   *
   * Usage:
   * try( Vec.Writer vw = vec.open() ) {
   *   vw.set(0, 3.32);
   *   vw.set(1, 4.32);
   *   vw.set(2, 5.32);
   * }
   */
  public final class Writer implements java.io.Closeable {
    private ChunkAry _cache;
    int _j;
    private ChunkAry chk(long i) {
      ChunkAry c = _cache;
      return (c != null && c.start() <= i && i < c.start()+ c._len) ? c : (_cache = chunkForRow(i));
    }
    private Writer() { this(0);}
    private Writer(int j) { preWriting(); }

    public final void set( long i, long   l) { set(i,_j,l);}
    public final void set( long i, int j, long   l) {
      ChunkAry ck = chk(i);
      ck.set(ck.chunkRelativeOffset(i),j, l);
    }
    public final void set( long i, double   d) { set(i,_j,d);}
    public final void set( long i, int j, double d) {
      ChunkAry ck = chk(i);
      ck.set(ck.chunkRelativeOffset(i),j, d);
    }
    public final void set( long i, float  f) { set(i,f);}
    public final void set( long i, int j, float  f) {
      ChunkAry ck = chk(i);
      ck.set(ck.chunkRelativeOffset(i),j, f);
    }
    public final void setNA( long i        ) { setNA(i,_j); }
    public final void setNA( long i, int j) {
      ChunkAry ck = chk(i);
      ck.setNA(ck.chunkRelativeOffset(i),j);
    }
    public final void set( long i,String str){ set(i,_j,str); }
    public final void set( long i,int j, String str){
      ChunkAry ck = chk(i);
      ck.set(ck.chunkRelativeOffset(i),j,str);
    }
    public Futures close(Futures fs) { return postWrite(closeLocal(fs)); }
    public void close() { close(new Futures()).blockForPending(); }
  }

  /** Create a writer for bulk serial writes into this Vec.
   *  @return A Writer for bulk serial writes */
  public final Writer open() { return new Writer(); }

  /** Close all chunks that are local (not just the ones that are homed)
   *  This should only be called from a Writer object */
  private Futures closeLocal(Futures fs) {
    int nc = nChunks();
    for( int i=0; i<nc; i++ )
      if( H2O.containsKey(chunkKey(i)) )
        chunkForChunkIdx(i).close(fs);
    return fs;                  // Flow-coding
  }


  /** Pretty print the Vec: {@code [#elems, min/mean/max]{chunks,...}}
   *  @return Brief string representation of a Vec */
  @Override public String toString() {
    RollupsAry rs = tryFetchRollups();
    String s = " len = " + length() + ", " + ((rs == null)?"[rollups not computed]":rs.toString());
    int nc = nChunks();
    for( int i=0; i<nc; i++ ) {
      s += chunkKey(i).home_node()+":"+chunk2StartElem(i)+":";
      // CNC: Bad plan to load remote data during a toString... messes up debug printing
      // Stupidly chunkForChunkIdx loads all data locally
      // s += chunkForChunkIdx(i).getClass().getSimpleName().replaceAll("Chunk","")+", ";
    }
    return s+"}]";
  }

  /**
   * Turn this Vec into a TwoDimTable of a 1-column Frame
   * @param off index of first element to include
   * @param len number of elements to include
   * @return TwoDimTable that can be toString()'ed, etc.
   */
  public TwoDimTable toTwoDimTable(int off, int len) {
    return new Frame(this).toTwoDimTable(off, len);
  }

  /**
   * Turn this Vec into a TwoDimTable of a 1-column Frame
   * @return TwoDimTable that can be toString()'ed, etc.
   */
  public TwoDimTable toTwoDimTable() {
    int len = (int)Math.min(Integer.MAX_VALUE, length());
    return new Frame(this).toTwoDimTable(0,len);
  }

  /**
   * Convenience method for converting to a categorical vector.
   * @return A categorical vector based on the contents of the original vector.
   */
  public Vec toCategoricalVec() {return VecUtils.toCategoricalVec(this);}
  /**
   * Convenience method for converting to a string vector.
   * @return A string vector based on the contents of the original vector.
   */
  public Vec toStringVec() {return VecUtils.toStringVec(this);}
  /**
   * Convenience method for converting to a numeric vector.
   * @return A numeric vector based on the contents of the original vector.
   */
  public Vec toNumericVec() {return VecUtils.toNumericVec(this);}

  /** True if two Vecs are equal.  Checks for equal-Keys only (so it is fast)
   *  and not equal-contents.
   *  @return True if two Vecs are equal */
  @Override public boolean equals( Object o ) {
    return o instanceof Vec && ((Vec)o)._key.equals(_key);
  }
  /** Vec's hashcode, which is just the Vec Key hashcode.
   *  @return Vec's hashcode */
  @Override public int hashCode() { return _key.hashCode(); }

  /** Remove associated Keys when this guy removes.  For Vecs, remove all
   *  associated Chunks.
   *  @return Passed in Futures for flow-coding  */
  @Override public Futures remove_impl( Futures fs ) {
    // Bulk dumb local remove - no JMM, no ordering, no safety.
    final int ncs = nChunks();
    new MRTask() {
      @Override public void setupLocal() { bulk_remove(_key,ncs); }
    }.doAllNodes();
    return fs;
  }
  // Bulk remove: removes LOCAL keys only, without regard to total visibility.
  // Must be run in parallel on all nodes to preserve semantics, completely
  // removing the Vec without any JMM communication.
  static void bulk_remove( Key vkey, int ncs ) {
    for( int i=0; i<ncs; i++ ) {
      Key kc = chunkKey(vkey,i);
      H2O.raw_remove(kc);
    }
    Key kr = chunkKey(vkey,-2); // Rollup Stats
    H2O.raw_remove(kr);
    H2O.raw_remove(vkey);
  }

  /** Write out K/V pairs */
  @Override protected AutoBuffer writeAll_impl(AutoBuffer ab) {
    int ncs = nChunks();
    for( int i=0; i<ncs; i++ ) {
      Key ck = chunkKey(i);
      ab.put(DKV.getGet(ck));   // Pull all Chunks local
      if( !ck.home() ) H2O.raw_remove(ck); // Remove the non-local ones as you go
    }
    return super.writeAll_impl(ab);
  }
  @Override protected Keyed readAll_impl(AutoBuffer ab, Futures fs) { 
    int ncs = nChunks();
    for( int i=0; i<ncs; i++ )
      DKV.put(chunkKey(i),ab.get(Chunk.class),fs,true); // Push chunk remote; do not cache local
    return super.readAll_impl(ab,fs);
  }

  // ======= Whole Vec Transformations ======

  /** Always makes a copy of the given vector which shares the same group as
   *  this Vec.  This can be expensive operation since it can force copy of
   *  data among nodes.
   *
   * @param vec vector which is intended to be copied
   * @return a copy of vec which shared the same {@link VectorGroup} with this vector
   */
  public Vec align(final Vec vec) {
    return new Frame(this).makeCompatible(new Frame(vec),true);
  }


  /** Make a Vec adapting this cal vector to the 'to' categorical Vec.  The adapted
   *  CategoricalWrappedVec has 'this' as it's masterVec, but returns results in the 'to'
   *  domain (or just past it, if 'this' has elements not appearing in the 'to'
   *  domain). */
  public CategoricalWrappedVec adaptTo( String[] domain ) {
    return new CategoricalWrappedVec(group().addVec(),_rowLayout,domain,this._key);
  }


  /**
   * Get Vec from the same vector group as src by it's vector group id.
   * @param sameGroupVec
   * @param vecId
   * @return
   */
  public static Key<Vec> getVecKeyById(Vec sameGroupVec, int vecId){
    byte [] bits = sameGroupVec._key._kb.clone();
    bits[0] = Key.VEC;
    UnsafeUtils.set4(bits,2,vecId);
    return Key.make(bits);
  }

  public static Key<Vec> setChunkId(Key vecKey, int chunkId){
    UnsafeUtils.set4(vecKey._kb,6,chunkId);
    return vecKey;
  }

  /** Class representing the group of vectors.
   *
   *  Vectors from the same group have same distribution of chunks among nodes.
   *  Each vector is member of exactly one group.  Default group of one vector
   *  is created for each vector.  Group of each vector can be retrieved by
   *  calling group() method;
   *  
   *  The expected mode of operation is that user wants to add new vectors
   *  matching the source.  E.g. parse creates several vectors (one for each
   *  column) which are all colocated and are colocated with the original
   *  bytevector.
   *  
   *  To do this, user should first ask for the set of keys for the new vectors
   *  by calling addVecs method on the target group.
   *  
   *  Vectors in the group will have the same keys except for the prefix which
   *  specifies index of the vector inside the group.  The only information the
   *  group object carries is its own key and the number of vectors it
   *  contains (deleted vectors still count).
   *  
   *  Because vectors (and chunks) share the same key-pattern with the group,
   *  default group with only one vector does not have to be actually created,
   *  it is implicit.
   *  
   *  @author tomasnykodym
   */
  public static class VectorGroup extends Keyed<VectorGroup> {
    /** The common shared vector group for very short vectors */
    public static final VectorGroup VG_LEN1 = new VectorGroup();
    // The number of Vec keys handed out by the this VectorGroup already.
    // Updated by overwriting in a TAtomic.
    final int _len;

    // New empty VectorGroup (no Vecs handed out)
    public VectorGroup() { super(init_key()); _len = 0; }
    static private Key init_key() { 
      byte[] bits = new byte[26];
      bits[0] = Key.GRP;
      bits[1] = -1;
      UnsafeUtils.set4(bits, 2, -1);
      UnsafeUtils.set4(bits, 6, -1);
      UUID uu = UUID.randomUUID();
      UnsafeUtils.set8(bits,10,uu.getLeastSignificantBits());
      UnsafeUtils.set8(bits,18,uu. getMostSignificantBits());
      return Key.make(bits);
    }

    // Clone an old vector group, setting a new len
    private VectorGroup(Key key, int newlen) { super(key); _len = newlen; }

    /** Returns Vec Key from Vec id#.  Does NOT allocate a Key id#
     *  @return Vec Key from Vec id# */
    public Key<Vec> vecKey(int vecId) {
      byte [] bits = _key._kb.clone();
      bits[0] = Key.VEC;
      UnsafeUtils.set4(bits,2,vecId);
      return Key.make(bits);
    }



    /** Task to atomically add vectors into existing group.
     *  @author tomasnykodym   */
    private final static class AddVecs2GroupTsk extends TAtomic<VectorGroup> {
      final Key _key;
      int _n;          // INPUT: Keys to allocate; OUTPUT: start of run of keys
      private AddVecs2GroupTsk(Key key, int n){_key = key; _n = n;}
      @Override protected VectorGroup atomic(VectorGroup old) {
        int n = _n;             // how many
        // If the old group is missing, assume it is the default group-of-self
        // (having 1 ID already allocated for self), not a new group with
        // zero prior vectors.
        _n = old==null ? 1 : old._len; // start of allocated key run
        return new VectorGroup(_key, n+_n);
      }
    }

    /** Reserve a range of keys and return index of first new available key
     *  @return Vec id# of a range of Vec keys in this group */
    public int reserveKeys(final int n) {
      AddVecs2GroupTsk tsk = new AddVecs2GroupTsk(_key, n);
      tsk.invoke(_key);
      return tsk._n;
    }

    /** Gets the next n keys of this group.
     *  @param n number of keys to make
     *  @return arrays of unique keys belonging to this group.  */
    public Key<Vec>[] addVecs(final int n) {
      int nn = reserveKeys(n);
      Key<Vec>[] res = (Key<Vec>[])new Key[n];
      for( int i = 0; i < n; ++i )
        res[i] = vecKey(i + nn);
      return res;
    }
    /** Shortcut for {@code addVecs(1)}.
     *  @see #addVecs(int)
     *  @return a new Vec Key in this group   */
    public Key<Vec> addVec() { return addVecs(1)[0]; }

    // -------------------------------------------------
    static boolean sameGroup(Vec v1, Vec v2) {
      byte[] bits1 = v1.groupKey()._kb;
      byte[] bits2 = v2.groupKey()._kb;
      if( bits1.length != bits2.length )
        return false;
      int res  = 0;
      for( int i = KEY_PREFIX_LEN; i < bits1.length; i++ )
        res |= bits1[i] ^ bits2[i];
      return res == 0;
    }

    /** Pretty print the VectorGroup
     *  @return String representation of a VectorGroup */
    @Override public String toString() {
      return "VecGrp "+_key.toString()+", next free="+_len;
    }
    // Return current VectorGroup index; used for tests
    public int len() { return _len; }

    /** True if two VectorGroups are equal 
     *  @return True if two VectorGroups are equal */
    @Override public boolean equals( Object o ) {
      return o instanceof VectorGroup && ((VectorGroup)o)._key.equals(_key);
    }
    /** VectorGroups's hashcode
     *  @return VectorGroups's hashcode */
    @Override public int hashCode() { return _key.hashCode(); }
    @Override protected long checksum_impl() { throw H2O.fail(); }
    // Fail to remove a VectorGroup unless you also remove all related Vecs,
    // Chunks, Rollups (and any Frame that uses them), etc.
    @Override protected Futures remove_impl( Futures fs ) { throw H2O.fail(); }
    /** Write out K/V pairs */
    @Override protected AutoBuffer writeAll_impl(AutoBuffer ab) { throw H2O.fail(); }
    @Override protected Keyed readAll_impl(AutoBuffer ab, Futures fs) { throw H2O.unimpl(); }
  }

  // ---------------------------------------
  // Unify ESPC arrays on the local node as much as possible.  This is a
  // similar problem to what TypeMap solves: sometimes I have a rowLayout index
  // and want the matching ESPC array, and sometimes I have the ESPC array and
  // want an index.  The operation is frequent and must be cached locally, but
  // must be globally consistent.  Hence a "miss" in the local cache means we
  // need to fetch from global state, and sometimes update the global state
  // before fetching.

  public static class ESPC extends Keyed<ESPC> {
    static private NonBlockingHashMap<Key,ESPC> ESPCS = new NonBlockingHashMap<>();

    // Array of Row Layouts (Element Start Per Chunk) ever seen by this
    // VectorGroup.  Shared here, amongst all Vecs using the same row layout
    // (instead of each of 1000's of Vecs having a copy, each of which is
    // nChunks long - could be millions).
    //
    // Element-start per chunk.  Always zero for chunk 0.  One more entry than
    // chunks, so the last entry is the total number of rows.
    public final long[][] _espcs;

    private ESPC(Key key, long[][] espcs) { super(key); _espcs = espcs;}
    // Fetch from the local cache
    private static ESPC getLocal( Key kespc ) {
      ESPC local = ESPCS.get(kespc);
      if( local != null ) return local;
      ESPCS.putIfAbsent(kespc,new ESPC(kespc,new long[0][])); // Racey, not sure if new or old is returned
      return ESPCS.get(kespc);
    }

    // Fetch from remote, and unify as needed
    private static ESPC getRemote( ESPC local, Key kespc ) {
      final ESPC remote = DKV.getGet(kespc);
      if( remote == null || remote == local ) return local; // No change

      // Something New?  If so, we need to unify the sharable arrays with a
      // "smashing merge".  Every time a remote instance of a ESPC is updated
      // (to add new ESPC layouts), and it is pulled locally, the new copy
      // brings with it a complete copy of all ESPC arrays - most of which
      // already exist locally in the old ESPC instance.  Since these arrays
      // are immutable and monotonically growing, it's safe (and much more
      // efficient!) to make the new copy use the old copies arrays where
      // possible.
      long[][] local_espcs = local ._espcs;
      long[][] remote_espcs= remote._espcs;
      // Spin attempting to move the larger remote value into the local cache
      while( true ) {
        // Is the remote stale, and the local value already larger?  Can happen
        // if the local is racily updated by another thread, after this thread
        // reads the remote value (which then gets invalidated, and updated to
        // a new larger value).
        if( local_espcs.length >= remote_espcs.length ) return local;
        // Use my (local, older, more heavily shared) ESPCs where possible.
        // I.e., the standard remote read will create new copies of all ESPC
        // arrays, but the *local* copies are heavily shared.  All copies are
        // equal, but using the same shared copies cuts down on copies.
        System.arraycopy(local._espcs, 0, remote._espcs, 0, local._espcs.length);
        // Here 'remote' is larger than 'local' (but with a shared common prefix).
        // Attempt to update local cache with the larger value
        ESPC res = ESPCS.putIfMatch(kespc,remote,local);  // Update local copy with larger
        // if res==local, then update succeeded, table has 'remote' (the larger object).
        if( res == local ) return remote;
        // if res!=local, then update failed, and returned 'res' is probably
        // larger than either remote or local
        local = res;
        local_espcs = res._espcs;
        assert remote_espcs== remote._espcs; // unchanging final field
      }
    }

    /** Get the ESPC for a Vec.  Called once per new construction or read_impl.  */
    public static long[] espc( Vec v ) {
      final int r = v._rowLayout;
      if( r == -1 ) return null; // Never was any row layout
      // Check the local cache
      final Key kespc = espcKey(v._key);
      ESPC local = getLocal(kespc);
      if( r < local._espcs.length ) return local._espcs[r];
      // Now try to refresh the local cache from the remote cache
      final ESPC remote = getRemote( local, kespc);
      if( r < remote._espcs.length ) return remote._espcs[r];
      throw H2O.fail("Vec "+v._key+" asked for layout "+r+", but only "+remote._espcs.length+" layouts defined");
    }

    // Check for a prior matching ESPC
    private static int find_espc( long[] espc, long[][] espcs ) {
      // Check for a local pointer-hit first:
      for( int i=0; i<espcs.length; i++ ) if( espc==espcs[i] ) return i;
      // Check for a local deep equals next:
      for( int i=0; i<espcs.length; i++ )
        if( espc.length==espcs[i].length && Arrays.equals(espc,espcs[i]) ) 
          return i;
      return -1;                // No match
    }

    /** Get the shared ESPC index for this layout.  Will return an old layout
     *  if one matches, otherwise will atomically update the ESPC to set
     *  a new layout.  The expectation is that new layouts are rare: once per
     *  parse, and perhaps from filtering MRTasks, or data-shuffling.  */
    public static int rowLayout( Key key, final long[] espc ) {
      Key kespc = espcKey(key);
      ESPC local = getLocal(kespc);
      int idx = find_espc(espc,local._espcs);
      if( idx != -1 ) return idx;

      // See if the ESPC is in the LOCAL DKV - if not it might have been
      // invalidated, and a refetch might get a new larger ESPC with the
      // desired layout.
      if( !H2O.containsKey(kespc) ) {
        local = getRemote(local,kespc);      // Fetch remote, merge as needed
        idx = find_espc(espc, local._espcs); // Retry
        if( idx != -1 ) return idx;
      }
      
      // Send the ESPC over to the ESPC master, and request it get
      // inserted.
      new TAtomic<ESPC>() {
        @Override public ESPC atomic( ESPC old ) {
          if( old == null ) return new ESPC(_key,new long[][]{espc});
          long[][] espcs = old._espcs;
          int idx = find_espc(espc,espcs);
          if( idx != -1 ) return null; // Abort transaction, idx exists; client needs to refresh
          int len = espcs.length;
          espcs = Arrays.copyOf(espcs,len+1);
          espcs[len] = espc;    // Insert into array
          return new ESPC(_key,espcs);
        }
      }.invoke(kespc);
      // Refetch from master, try again
      ESPC reloaded = getRemote(local,kespc); // Fetch remote, merge as needed
      idx = find_espc(espc,reloaded._espcs);  // Retry
      assert idx != -1;                       // Must work now (or else the install failed!)
      return idx;
    }
    public static void clear() { ESPCS.clear(); }
    @Override protected long checksum_impl() { throw H2O.fail(); }
  }
  public Vec[] vecs() {return new Vec[]{this};}
}
