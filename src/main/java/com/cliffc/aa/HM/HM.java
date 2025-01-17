package com.cliffc.aa.HM;

import com.cliffc.aa.type.*;
import com.cliffc.aa.util.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.BiPredicate;

import static com.cliffc.aa.AA.ARG_IDX;
import static com.cliffc.aa.AA.unimpl;
import static com.cliffc.aa.type.TypeFld.Access;

// Combined Hindley-Milner and Global Constant Propagation typing.

// Complete stand-alone, for research.

// Treats HM as a Monotone Analysis Framework; converted to a worklist style.
// The type-vars are monotonically unified, gradually growing over time - and
// this is treated as the MAF lattice.  Some normal Algo-W work gets done in a
// prepass; e.g. discovering identifier sources (SSA form), and building the
// non-generative set.  Because of the non-local unification behavior type-vars
// include a "dependent Syntax" set; a set of Syntax elements put back on the
// worklist if this type unifies, beyond the expected parent and AST children.
//
// The normal HM unification steps are treated as the MAF transfer "functions",
// taking type-vars as inputs and producing new, unified, type-vars.  Because
// unification happens in-place (normal Tarjan disjoint-set union), the
// transfer "functions" are executed for side effects only, and return a
// progress flag.  The transfer functions are virtual calls on each Syntax
// element.  Some steps are empty because of the pre-pass (Let,Con).
//
// HM Bases include anything from the GCP lattice, and are generally sharper
// than e.g. 'int'.  Bases with values of '3' and "abc" are fine.  These are
// widened to the normal HM types if passed to any HM function; they remain
// sharp if returned or passed to primitives.  HM functions include the set of
// FIDXs used in the unification; this set is generally less precise than that
// from GCP.  HM function arguments that escape have their GCP type widened "as
// if" called from the most HM-general legal call site; otherwise GCP assumes
// escaping functions are never called and their arguments have unrealistic
// high flow types.
//
// HM includes polymorphic structures and fields (structural typing not duck
// typing), polymorphic nil-checking and an error type-var.  Both HM and GCP
// types fully support recursive types.
//
//
// Unification typically makes many temporary type-vars and immediately unifies
// them.  For efficiency, this algorithm checks to see if unification requires
// an allocation first, instead of just "allocate and unify".  The major place
// this happens is identifiers, which normally make a "fresh" copy of their
// type-var, then unify.  I use a combined "make-fresh-and-unify" unification
// algorithm there.  It is a structural clone of the normal unify, except that
// it lazily makes a fresh-copy of the left-hand-side on demand only; typically
// discovering that no fresh-copy is required.
//
// To engineer and debug the algorithm, the unification step includes a flag to
// mean "actually unify, and report a progress flag" vs "report if progress".
// The report-only mode is aggressively asserted for in the main loop; all
// Syntax elements that can make progress are asserted as on the worklist.
//
// GCP gets the normal MAF treatment, no surprises there.
//
// The combined algorithm includes transfer functions taking facts from both
// MAF lattices, producing results in the other lattice.
//
// For the GCP->HM direction, the HM 'if' has a custom transfer function
// instead of the usual one.  Unification looks at the GCP value, and unifies
// either the true arm, or the false arm, or both or neither.  In this way GCP
// allows HM to avoid picking up constraints from dead code.
//
// Also for GCP->HM, the HM ground terms or base terms include anything from
// the GCP lattice.
//
// For the HM->GCP direction, the GCP 'apply' has a customer transfer function
// where the result from a call gets lifted (JOINed) based on the matching GCP
// inputs - and the match comes from using the same HM type-var on both inputs
// and outputs.  This allows e.g. "map" calls which typically merge many GCP
// values at many applies (call sites) and thus end up typed as a Scalar to
// Scalar, to improve the GCP type on a per-call-site basis.
//
// Test case 45 demonstrates this combined algorithm, with a program which can
// only be typed using the combination of GCP and HM.
//
// BNF for the "core AA" syntax:
//    e  = number | string | primitives | (fe0 fe1*) | { id* -> fe0 } | id | id = fe0; fe1 | @{ (label = fe0)* }
//    fe = e | fe.label                 // optional field after expression
//
// BNF for the "core AA" pretty-printed types:
//    T = X | X:T | { X* -> X } | base | @{ (label = X)* } | T? | Error
//    base = any lattice element, all are nilable
//    Multiple stacked T????s collapse
//


// idea: err keeps the un-unifiable parts separate, better errors later


public class HM {
  // Mapping from primitive name to PrimSyn
  static final HashMap<String,PrimSyn> PRIMSYNS = new HashMap<>();

  static final boolean DEBUG_LEAKS=false;
  static { BitsAlias.init0(); BitsFun.init0(); }

  static final boolean DO_HM  = true;
  static final boolean DO_GCP = true;

  public static Root hm( String sprog ) {
    Worklist work = new Worklist();
    PrimSyn.WORK=work;

    for( PrimSyn prim : new PrimSyn[]{new If(), new Pair(), new EQ(), new EQ0(), new Mul(), new Add(), new Dec(), new Str(), new Triple(), new Factor(), new IsEmpty(), new NotNil()} )
      PRIMSYNS.put(prim.name(),prim);

    // Parse
    Root prog = parse( sprog );

    // Prep for SSA: pre-gather all the (unique) ids
    int cnt_syns = prog.prep_tree(null,null,work);
    int init_T2s = T2.CNT;
    main_work_loop(prog,work);
    assert prog.more_work(work);
    if( DO_GCP && prog.lower_escaping_vals(work) ) {
      main_work_loop(prog,work);
      assert !prog.lower_escaping_vals(work) && work.len()==0;
    }

    System.out.println("Initial T2s: "+init_T2s+", Prog size: "+cnt_syns+", worklist iters: "+work._cnt+", T2s: "+T2.CNT);
    return prog;
  }

  static void main_work_loop(Root prog, Worklist work) {

    while( work.len()>0 ) {     // While work
      int oldcnt = T2.CNT;      // Used for cost-check when no-progress
      assert work._cnt<3000;
      Syntax syn = work.pop();  // Get work
      if( DO_HM ) {
        T2 old = syn._hmt;        // Old value for progress assert
        if( syn.hm(work) ) {
          assert syn.debug_find()==old.debug_find(); // monotonic: unifying with the result is no-progress
          syn.add_hm_work(work);     // Push affected neighbors on worklist
        } else {
          assert !DEBUG_LEAKS || oldcnt==T2.CNT;  // No-progress consumes no-new-T2s
        }
      }
      if( DO_GCP ) {
        Type old = syn._flow;
        Type t = syn.val(work);
        if( t!=old ) {           // Progress
          assert old.isa(t);     // Monotonic falling
          syn._flow = t;         // Update type
          if( syn._par!=null ) { // Generally, parent needs revisit
            work.push(syn._par); // Assume parent needs revisit
            syn._par.add_val_work(syn,work); // Push affected neighbors on worklist
          }
        }
      }

      // VERY EXPENSIVE ASSERT: O(n^2).  Every Syntax that makes progress is on the worklist
      assert prog.more_work(work);
    }
  }

  static void reset() {
    BitsAlias.reset_to_init0();
    BitsFun.reset_to_init0();
    PRIMSYNS.clear();
    Lambda.FUNS.clear();
    T2.reset();
    PrimSyn.reset();
  }

  // ---------------------------------------------------------------------
  // Program text for parsing
  private static int X;
  private static byte[] BUF;
  @Override public String toString() { return new String(BUF,X,BUF.length-X); }
  static Root parse( String s ) {
    X = 0;
    BUF = s.getBytes();
    Syntax prog = fterm();
    if( skipWS() != -1 ) throw unimpl("Junk at end of program: "+new String(BUF,X,BUF.length-X));
    // Inject IF at root
    return new Root(prog);
  }
  static Syntax term() {
    if( skipWS()==-1 ) return null;
    if( isDigit(BUF[X]) ) return number();
    if( BUF[X]=='"' ) return string();

    if( BUF[X]=='(' ) {         // Parse an Apply
      X++;                      // Skip paren
      Syntax fun = fterm();
      Ary<Syntax> args = new Ary<>(new Syntax[1],0);
      while( skipWS()!= ')' && X<BUF.length ) args.push(fterm());
      require(')');
      // Guarding if-nil test inserts an upcast.  This is a syntactic transform only.
      if( fun instanceof If &&
          args.at(0) instanceof Ident ) {
        Ident id = (Ident)args.at(0);
        args.set(1,new Apply(new Lambda(false,args.at(1), id._name),
                             new Apply(new NotNil(),new Ident(id._name))));
      }
      return new Apply(fun,args.asAry());
    }

    if( BUF[X]=='{' ) {         // Lambda of 1 or 2 args
      X++;                      // Skip paren
      Ary<String> args = new Ary<>(new String[1],0);
      while( skipWS()!='-' ) args.push(id());
      require("->");
      Syntax body = fterm();
      require('}');
      return new Lambda(true,body,args.asAry());
    }
    // Let or Id
    if( isAlpha0(BUF[X]) ) {
      String id = id();
      if( skipWS()!='=' ) {
        PrimSyn prim = PRIMSYNS.get(id); // No shadowing primitives or this lookup returns the prim instead of the shadow
        return prim==null ? new Ident(id) : prim.make(); // Make a prim copy with fresh HM variables
      }
      // Let expression; "id = term(); term..."
      X++;                      // Skip '='
      Syntax def = fterm();
      require(';');
      return new Let(id,def,fterm());
    }

    // Structure
    if( BUF[X]=='@' ) {
      X++;
      require('{');
      Ary<String>  ids = new Ary<>(String.class);
      Ary<Syntax> flds = new Ary<>(Syntax.class);
      while( skipWS()!='}' && X < BUF.length ) {
        String id = require('=',id());
        Syntax fld = fterm();
        if( fld==null ) throw unimpl("Missing term for field "+id);
        ids .push( id);
        flds.push(fld);
        if( skipWS()==',' ) X++;
      }
      require('}');
      return new Struct(ids.asAry(),flds.asAry());
    }

    throw unimpl("Unknown syntax");
  }
  // Parse a term with an optional following field.
  private static Syntax fterm() {
    Syntax term=term();
    while( true ) {
      if( term==null || skipWS()!='.' ) return term;
      X++;
      term = new Field(id(),term);
    }
  }
  private static final SB ID = new SB();
  private static String id() {
    ID.clear();
    while( X<BUF.length && isAlpha1(BUF[X]) )
      ID.p((char)BUF[X++]);
    String s = ID.toString().intern();
    if( s.length()==0 ) throw unimpl("Missing id");
    return s;
  }
  private static Syntax number() {
    if( BUF[X]=='0' ) { X++; return new Con(Type.XNIL); }
    int sum=0;
    while( X<BUF.length && isDigit(BUF[X]) )
      sum = sum*10+BUF[X++]-'0';
    if( X>= BUF.length || BUF[X]!='.' )
      return new Con(TypeInt.con(sum));
    // Ambiguous '.' in: 2.3 vs 2.x (field load from a number)
    if( X+1<BUF.length && isAlpha0(BUF[X+1]) )
      return new Con(TypeInt.con(sum));
    X++;
    float f = (float)sum;
    f = f + (BUF[X++]-'0')/10.0f;
    return new Con(TypeFlt.con(f));
  }
  private static Syntax string() {
    int start = ++X;
    while( X<BUF.length && BUF[X]!='"' ) X++;
    return require('"', new Con(TypeMemPtr.make(BitsAlias.STRBITS,TypeStr.con(new String(BUF,start,X-start).intern()))));
  }
  private static byte skipWS() {
    while( X<BUF.length && isWS(BUF[X]) ) X++;
    return X==BUF.length ? -1 : BUF[X];
  }
  private static boolean isWS    (byte c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
  private static boolean isDigit (byte c) { return '0' <= c && c <= '9'; }
  private static boolean isAlpha0(byte c) { return ('a'<=c && c <= 'z') || ('A'<=c && c <= 'Z') || (c=='_') || (c=='*') || (c=='?') || (c=='+'); }
  private static boolean isAlpha1(byte c) { return isAlpha0(c) || ('0'<=c && c <= '9') || (c=='/'); }
  private static void require(char c) { if( skipWS()!=c ) throw unimpl("Missing '"+c+"'"); X++; }
  private static <T> T require(char c, T t) { require(c); return t; }
  private static void require(String s) {
    skipWS();
    for( int i=0; i<s.length(); i++ )
      if( X+i >= BUF.length || BUF[X+i]!=s.charAt(i) )
        throw unimpl("Missing '"+s+"'");
    X+=s.length();
  }

  // ---------------------------------------------------------------------
  // Worklist of Syntax nodes
  private static class Worklist {
    public int _cnt;                                          // Count of items ever popped (not the current length)
    private final Ary<Syntax> _ary = new Ary<>(Syntax.class); // For picking random element
    private final HashSet<Syntax> _work = new HashSet<>();    // For preventing dups
    public int len() { return _ary.len(); }
    public void push(Syntax s) { if( s!=null && !_work.contains(s) ) _work.add(_ary.push(s)); }
    public Syntax pop() { Syntax s = _ary.pop();_cnt++;            _work.remove(s); return s; }
    //public Syntax pop() { Syntax s = _ary.del(  _cnt++%_ary._len); _work.remove(s); return s; }
    public boolean has(Syntax s) { return _work.contains(s); }
    public void addAll(Ary<? extends Syntax> ss) { if( ss != null ) for( Syntax s : ss ) push(s); }
    public void clear() {
      _cnt=0;
      _ary.clear();
      _work.clear();
    }
    @Override public String toString() { return _ary.toString(); }
  }

  // ---------------------------------------------------------------------
  // Small classic tree of T2s, immutable, with sharing at the root parts.
  static class VStack implements Iterable<T2> {
    final VStack _par;
    private T2 _nongen;
    final int _d;
    VStack( VStack par, T2 nongen ) { _par=par; _nongen=nongen; _d = par==null ? 0 : par._d+1; }
    T2 nongen() {
      T2 n = _nongen.find();
      return n==_nongen ? n : (_nongen=n);
    }
    @Override public String toString() {
      // Collect dups across the forest of types
      VBitSet dups = new VBitSet();
      for( VStack vs = this; vs!=null; vs = vs._par )
        vs._nongen._get_dups(new VBitSet(),dups);
      // Now recursively print
      return str(new SB(),dups).toString();
    }
    SB str(SB sb, VBitSet dups) {
      _nongen.str(sb,new VBitSet(),dups,true);
      if( _par!=null ) _par.str(sb.p(" , "),dups);
      return sb;
    }
    @NotNull @Override public Iterator<T2> iterator() { return new Iter(); }
    private class Iter implements Iterator<T2> {
      private VStack _vstk;
      Iter() { _vstk=VStack.this; }
      @Override public boolean hasNext() { return _vstk!=null; }
      @Override public T2 next() { T2 v = _vstk.nongen(); _vstk = _vstk._par;  return v; }
    }
  }

  // ---------------------------------------------------------------------
  static abstract class Syntax {
    Syntax _par;                // Parent in the AST
    VStack _nongen;             // Non-generative type variables
    T2 _hmt;                    // Current HM type
    T2 find() {                 // U-F find
      T2 t = _hmt.find();
      return t== _hmt ? t : (_hmt =t);
    }
    T2 debug_find() { return _hmt.debug_find(); } // Find, without the roll-up

    // Dataflow types.  Varies during a run of GCP.
    Type _flow;

    // Compute a new HM type.
    // If no change, return false.
    // If a change, return always true, however:
    // - If 'work' is null do not change/set anything.
    // - If 'work' is available, update the worklist.
    abstract boolean hm(Worklist work);

    abstract void add_hm_work(Worklist work); // Add affected neighbors to worklist

    // Compute and return (and do not set) a new GCP type for this syntax.
    abstract Type val(Worklist work);

    void add_val_work(Syntax child, Worklist work) {} // Add affected neighbors to worklist

    // First pass to "prepare" the tree; does e.g. Ident lookup, sets initial
    // type-vars and counts tree size.
    abstract int prep_tree(Syntax par, VStack nongen, Worklist work);
    final void prep_tree_impl( Syntax par, VStack nongen, Worklist work, T2 t ) {
      _par = par;
      _hmt = t;
      _flow= Type.XSCALAR;
      _nongen = nongen;
      work.push(this);
    }
    void prep_lookup_deps(Ident id) {}

    // Giant Assert: True if OK; all Syntaxs off worklist do not make progress
    abstract boolean more_work(Worklist work);
    final boolean more_work_impl(Worklist work) {
      if( DO_HM && !work.has(this) && hm(null) )   // Any more HM work?
        return false;           // Found HM work not on worklist
      Type t;
      if( DO_GCP && !(_flow.isa(t=val(null)) && (_flow==t || work.has(this))) )
        return false;           // Found GCP work not on worklist
      return true;
    }
    // Print for debugger
    @Override final public String toString() { return str(new SB()).toString(); }
    abstract SB str(SB sb);
    // Line-by-line print with more detail
    public String p() { return p0(new SB(), new VBitSet()).toString(); }
    final SB p0(SB sb, VBitSet dups) {
      _hmt._get_dups(new VBitSet(),dups);
      VBitSet visit = new VBitSet();
      p1(sb.i());
      if( DO_HM  ) _hmt .str(sb.p(", HMT="), visit,dups,true);
      if( DO_GCP ) _flow.str(sb.p(", GCP="),visit.clr(),null,true);
      sb.nl();
      return p2(sb.ii(1),dups).di(1);
    }
    abstract SB p1(SB sb);      // Self short print
    abstract SB p2(SB sb, VBitSet dups); // Recursion print
  }

  static class Con extends Syntax {
    final Type _con;
    Con(Type con) { super(); _con=con; }
    @Override SB str(SB sb) { return p1(sb); }
    @Override SB p1(SB sb) { return sb.p(_con.toString()); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
    @Override boolean hm(Worklist work) { return false; }
    @Override Type val(Worklist work) { return _con; }
    @Override void add_hm_work(Worklist work) { }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      // A '0' turns into a nilable leaf.
      T2 base = _con==Type.XNIL ? T2.make_nil(T2.make_leaf()) : T2.make_base(_con);
      prep_tree_impl(par, nongen, work, base);
      return 1;
    }
    @Override boolean more_work(Worklist work) { return more_work_impl(work); }
  }


  static class Ident extends Syntax {
    final String _name;         // The identifier name
    Syntax _def;                // Cached syntax defining point
    int _idx;                   // Index in Lambda (which arg of many)
    T2 _idt;                    // Cached type var for the name in scope
    Ident(String name) { _name=name; }
    @Override SB str(SB sb) { return p1(sb); }
    @Override SB p1(SB sb) { return sb.p(_name); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
    T2 idt() {
      T2 idt = _idt.find();
      return idt==_idt ? idt : (_idt=idt);
    }
    @Override boolean hm(Worklist work) {
      return idt().fresh_unify(find(),_nongen,work);
    }
    @Override void add_hm_work(Worklist work) {
      work.push(_par);
      if( _par!=null && idt().nongen_in(_par._nongen) ) // Got captured in some parent?
        idt().add_deps_work(work);  // Need to revisit dependent ids
      if( _par instanceof Apply && ((Apply)_par)._fun instanceof NotNil )
        work.push(((Apply)_par)._fun);
    }
    @Override Type val(Worklist work) {
      return _def instanceof Let ? ((Let)_def)._def._flow : ((Lambda)_def)._types[_idx];
    }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      for( Syntax syn = _par; syn!=null; syn = syn._par )
        syn.prep_lookup_deps(this);

      // Lookup, and get the T2 type var and a pointer to the flow type.
      for( Syntax syn = _par; syn!=null; syn = syn._par ) {
        if( syn instanceof Lambda ) {
          Lambda lam = (Lambda)syn;
          if( (_idx = Util.find(lam._args,_name)) != -1 )
            return _init(lam,lam.targ(_idx));
        } else if( syn instanceof Let ) {
          Let let = (Let)syn;  _idx=-1;
          if( Util.eq(let._arg0,_name) )
            return _init(let,let._targ);
        }
      }
      throw new RuntimeException("Parse error, "+_name+" is undefined in "+_par);
    }
    private int _init(Syntax def,T2 idt) { _def = def; _idt = idt; return 1; }
    @Override boolean more_work(Worklist work) { return more_work_impl(work); }
  }


  static class Lambda extends Syntax {
    // Map from FIDXs to Lambdas
    static final NonBlockingHashMapLong<Lambda> FUNS = new NonBlockingHashMapLong<>();
    private final boolean _is_func_input; // Force-widen base inputs by default
    final String[] _args;                 // Lambda argument names
    final Syntax _body;                   // Lambda body
    final T2[] _targs;                    // HM argument types
    final Type[] _types;                  // Flow argument types
    final int _fidx;                      // Unique function idx

    Lambda(boolean is_func_input, Syntax body, String... args) {
      _is_func_input = is_func_input;
      _args=args;
      _body=body;
      // Type variables for all arguments
      _targs = new T2[args.length];
      for( int i=0; i<args.length; i++ ) _targs[i] = T2.make_leaf();
      // Flow types for all arguments
      _types = new Type[args.length];
      for( int i=0; i<args.length; i++ ) _types[i] = Type.XSCALAR;
      // A unique FIDX for this Lambda
      _fidx = BitsFun.new_fidx();
      FUNS.put(_fidx,this);
      _flow = val(null);
    }
    @Override SB str(SB sb) {
      sb.p("{ ");
      for( String arg : _args ) sb.p(arg).p(' ');
      return _body.str(sb.p("-> ")).p(" }");
    }
    @Override SB p1(SB sb) {
      sb.p("{ ");
      for( int i=0; i<_args.length; i++ ) {
        sb.p(_args[i]);
        if( DO_HM  ) sb.p(", HMT=" ).p(targ(i).toString());
        if( DO_GCP ) sb.p(", GCP=").p(_types[i]);
        sb.nl().i().p("  ");
      }
      return sb.p(" -> ... } ");
    }
    @Override SB p2(SB sb, VBitSet dups) { return _body.p0(sb,dups); }
    T2 targ(int i) { T2 targ = _targs[i].find(); return targ==_targs[i] ? targ : (_targs[i]=targ); }
    @Override boolean hm(Worklist work) {
      // The normal lambda work
      T2 old = find();
      if( old.is_err() ) return false;
      assert old.is_fun();
      boolean progress = false;
      for( int i=0; i<_targs.length; i++ )
        progress |= old.arg(""+i).unify(targ(i),work);
      return old.arg("ret").unify(_body.find(),work) | progress;
    }
    @Override void add_hm_work(Worklist work) {
      work.push(_par );
      work.push(_body);
      for( int i=0; i<_targs.length; i++ )
        if( targ(i).occurs_in_type(find()) ) work.addAll(targ(i)._deps);
    }
    @Override Type val(Worklist work) { return TypeFunPtr.make(_fidx,_args.length,Type.ANY); }
    // Ignore arguments, and return body type.  Very conservative.
    Type apply(Syntax[] args) { return _body._flow; }
    @Override void add_val_work(Syntax child, Worklist work) {
      // Body changed, all Apply sites need to recompute
      find().add_deps_work(work);
    }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      // Prep self
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      // Extend the nongen set by the new variables
      VStack vs = nongen;
      for( T2 targ : _targs ) vs = new VStack(vs, targ);
      // Prep the body
      int cnt = _body.prep_tree(this,vs,work) + 1;
      // Go ahead and pre-unify with a required function
      T2[] targs = Arrays.copyOf(_targs,_targs.length+1);
      targs[_targs.length] = _body.find();
      // Widen all constant base inputs for normal functions, but not for the
      // hidden internal one made for not-nil.
      find().unify(T2.make_fun(_is_func_input, BitsFun.make0(_fidx), targs),work);
      return cnt;
    }
    @Override void prep_lookup_deps(Ident id) {
      for( int i=0; i<_args.length; i++ )
        if( Util.eq(_args[i],id._name) ) _targs[i].push_update(id);
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      return _body.more_work(work);
    }
  }

  static class Let extends Syntax {
    final String _arg0;
    final Syntax _def, _body;
    T2 _targ;
    Let(String arg0, Syntax def, Syntax body) { _arg0=arg0; _body=body; _def=def; _targ=T2.make_leaf(); }
    @Override SB str(SB sb) { return _body.str(_def.str(sb.p(_arg0).p(" = ")).p("; ")); }
    @Override SB p1(SB sb) { return sb.p(_arg0).p(" = ... ; ..."); }
    @Override SB p2(SB sb, VBitSet dups) { _def.p0(sb,dups); return _body.p0(sb,dups); }
    @Override boolean hm(Worklist work) { return false;  }
    @Override void add_hm_work(Worklist work) {
      work.push(_par);
      work.push(_body);
      work.push(_def);
      work.addAll(_def.find()._deps);
    }
    @Override Type val(Worklist work) { return _body._flow; }
    @Override void add_val_work(Syntax child, Worklist work) {
      if( child==_def )
        _def.find().add_deps_work(work);
    }

    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      prep_tree_impl(par,nongen,work,_body._hmt);
      int cnt = _body.prep_tree(this,           nongen       ,work) +
                _def .prep_tree(this,new VStack(nongen,_targ),work);
      _hmt = _body._hmt;            // Unify 'Let._hmt' with the '_body'
      _targ.unify(_def.find(),work);
      return cnt+1;
    }
    @Override void prep_lookup_deps(Ident id) {
      if( Util.eq(id._name,_arg0) ) _targ.push_update(id);
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      return _body.more_work(work) && _def.more_work(work);
    }
  }

  static class Apply extends Syntax {
    final Syntax _fun;
    final Syntax[] _args;
    Apply(Syntax fun, Syntax... args) { _fun = fun; _args = args; }
    @Override SB str(SB sb) {
      _fun.str(sb.p("(")).p(" ");
      for( Syntax arg : _args )
        arg.str(sb).p(" ");
      return sb.unchar().p(")");
    }
    @Override SB p1(SB sb) { return sb.p("(...)"); }
    @Override SB p2(SB sb, VBitSet dups) {
      _fun.p0(sb,dups);
      for( Syntax arg : _args ) arg.p0(sb,dups);
      return sb;
    }

    // Unifiying these: make_fun(this.arg0 this.arg1 -> new     )
    //                      _fun{_fun.arg0 _fun.arg1 -> _fun.rez}
    @Override boolean hm(Worklist work) {
      boolean progress = false;

      // Progress if:
      //   _fun is not a function
      //   any arg-pair-unifies make progress
      //   this-unify-_fun.return makes progress
      T2 tfun = _fun.find();
      if( tfun.is_err() ) return find().unify(tfun,work);
      if( !tfun.is_fun() ) {    // Not a function, so progress
        if( work==null ) return true; // Will-progress & just-testing
        T2[] targs = new T2[_args.length+1];
        for( int i=0; i<_args.length; i++ )
          targs[i] = _args[i].find();
        targs[_args.length] = find(); // Return
        T2 nfun = T2.make_fun(true, BitsFun.EMPTY, targs);
        progress = tfun.unify(nfun,work);
        return tfun.find().is_err() ? find().unify(tfun.find(),work) : progress;
      }

      if( tfun._args.size() != _args.length+1 )
        progress = T2.make_err("Mismatched argument lengths").unify(find(), work);

      // Check for progress amongst arg pairs
      for( int i=0; i<_args.length; i++ ) {
        progress |= tfun.arg(""+i).unify(_args[i].find(),work);
        if( progress && work==null ) return true; // Will-progress & just-testing early exit
        if( (tfun=tfun.find()).is_err() ) return find().unify(tfun,work);
      }
      // Check for progress on the return
      progress |= find().unify(tfun.arg("ret"),work);
      if( (tfun=tfun.find()).is_err() ) return find().unify(tfun,work);

      return progress;
    }
    @Override void add_hm_work(Worklist work) {
      work.push(_par);
      for( Syntax arg : _args ) work.push(arg);
    }
    static private final HashMap<T2,Type> T2MAP = new HashMap<>();
    static private final NonBlockingHashMapLong<TypeStruct> WDUPS = new NonBlockingHashMapLong<>();
    @Override Type val(Worklist work) {
      Type flow = _fun._flow;
      if( !(flow instanceof TypeFunPtr) ) return flow.oob(Type.SCALAR);
      TypeFunPtr tfp = (TypeFunPtr)flow;
      // Have some functions, meet over their returns.
      Type rez = Type.XSCALAR;
      if( tfp._fidxs.test(1) ) rez = Type.SCALAR; // Unknown, passed-in function.  Returns the worst
      else if( tfp._fidxs == BitsFun.EMPTY ) rez = Type.SCALAR;
      else
        for( int fidx : tfp._fidxs )
          rez = rez.meet(Lambda.FUNS.get(fidx).apply(_args));
      if( rez==Type.XSCALAR ) // Fast path cutout, no improvement possible
        return rez;

      // Attempt to lift the result, based on HM types.  Walk the input HM type
      // and CCP flow type in parallel and create a mapping.  Then walk the
      // output HM type and CCP flow type in parallel, and join output CCP
      // types with the matching input CCP type.
      if( DO_HM ) {
        // Walk the inputs, building a mapping
        T2MAP.clear();
        for( Syntax arg : _args )
          { WDUPS.clear(); arg.find().walk_types_in(arg._flow); }

        // Walk the outputs, building an improved result
        WDUPS.clear();
        Type rez2 = find().walk_types_out(rez,this);
        Type rez3 = rez2.join(rez);   // Lift result
        assert _flow.isa(rez3) && rez3.isa(rez ); // Monotonic...
        rez = rez3; // Upgrade
      }
      return rez;
    }
    @Override void add_val_work(Syntax child, Worklist work) {
      // If function changes type, recompute self
      if( child==_fun ) work.push(this);
      // If an argument changes type, adjust the lambda arg types
      Type flow = _fun._flow;
      if( flow.above_center() ) return;
      if( !(flow instanceof TypeFunPtr) ) return;
      // Meet the actuals over the formals.
      for( int fidx : ((TypeFunPtr)flow)._fidxs ) {
        Lambda fun = Lambda.FUNS.get(fidx);
        if( fun!=null ) {
          fun.find().push_update(this); // Discovered as call-site; if the Lambda changes the Apply needs to be revisited.
          for( int i=0; i<fun._types.length; i++ ) {
            Type formal = fun._types[i];
            Type actual = _args[i]._flow;
            Type rez = formal.meet(actual);
            if( formal != rez ) {
              fun._types[i] = rez;
              fun.targ(i).add_deps_work(work);
              work.push(fun._body);
              if( i==0 && fun instanceof If ) work.push(fun); // Specifically If might need more unification
            }
          }
        }
      }
    }

    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      int cnt = 1+_fun.prep_tree(this,nongen,work);
      for( Syntax arg : _args ) cnt += arg.prep_tree(this,nongen,work);
      return cnt;
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      if( !_fun.more_work(work) ) return false;
      for( Syntax arg : _args ) if( !arg.more_work(work) ) return false;
      return true;
    }
  }


  static class Root extends Apply {
    static final Syntax[] NARGS = new Syntax[0];
    Root(Syntax body) { super(body); }
    @Override SB str(SB sb) { return _fun.str(sb); }
    @Override boolean hm(final Worklist work) { return find().unify(_fun.find(),work); }

    @Override void add_hm_work(Worklist work) { }
    @Override Type val(Worklist work) { return _fun._flow; }
    @Override void add_val_work(Syntax child, Worklist work) {
      if( child==_fun ) work.push(this);
    }
    // Root-widening is when Root acts as-if it is calling the returned
    // function with the worse-case legal args.
    static Type widen(T2 t2) { return t2.as_flow(); }
    boolean lower_escaping_vals(Worklist work) {
      // Root-widening needs to call all functions which can be returned from
      // the Root or from any function reachable from the Root via struct &
      // fields, or by being returned from another function.
      RVISIT.clear();
      RPROG = false;
      walk(_flow,work);
      return RPROG;
    }
    private static final VBitSet RVISIT = new VBitSet();
    private static boolean RPROG;
    private static void walk( Type flow, Worklist work) {
      if( RVISIT.tset(flow._uid) ) return;
      if( flow instanceof TypeFunPtr ) {
        BitsFun fidxs = ((TypeFunPtr)flow)._fidxs;
        assert !fidxs.test(1);
        if( fidxs==BitsFun.EMPTY ) return;
        for( int fidx : fidxs ) {
          Lambda fun = Lambda.FUNS.get(fidx);
          // For each returned function, assume Root calls all arguments with
          // worse-case values.
          for( int i=0; i<fun._types.length; i++ ) {
            Type formal = fun._types[i];
            Type actual = Root.widen(fun.targ(i));
            Type rez = formal.meet(actual);
            if( formal != rez ) {
              fun._types[i] = rez;
              work.addAll(fun.targ(i)._deps);
              work.push(fun._body);
              RPROG=true;
            }
          }
        }
        return;
      }
      if( flow instanceof TypeMemPtr ) {
        TypeMemPtr tmp = (TypeMemPtr)flow;
        if( tmp._obj instanceof TypeStr ) return;
        TypeStruct ts = ((TypeStruct)tmp._obj);
        for( TypeFld fld : ts.flds() )
          walk(fld._t,work);
        return;
      }
      if( flow instanceof TypeInt || flow instanceof TypeFlt ) return;
      if( flow==Type.ANY || flow == Type.SCALAR || flow == Type.NSCALR || flow == Type.XSCALAR || flow == Type.XNSCALR )
        return;
      throw unimpl();
    }


    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      int cnt = super.prep_tree(par,nongen,work);
      _hmt.push_update(this);
      return cnt;
    }

    // Expand functions to full signatures, recursively
    private static final VBitSet ADD_SIG = new VBitSet();
    Type flow_type() { ADD_SIG.clear(); return add_sig(_flow); }
    private static Type add_sig(Type t) {
      if( ADD_SIG.tset(t._uid) ) return t;
      if( t instanceof TypeFunPtr ) {
        TypeFunPtr fun = (TypeFunPtr)t;
        Type rez = Type.XSCALAR;
        for( int fidx : fun._fidxs )
          rez = rez.meet(Lambda.FUNS.get(fidx).apply(NARGS));
        Type rez2 = add_sig(rez);
        return TypeFunSig.make(TypeTuple.make_ret(rez2),TypeTuple.make_args());
      } else {
        return t;
      }
    }
  }


  // Structure or Records.
  static class Struct extends Syntax {
    final int _alias;
    final String[]  _ids;
    final Syntax[] _flds;
    Struct( String[] ids, Syntax[] flds ) {
      _ids=ids;
      _flds=flds;
      // Make a TMP
      _alias = BitsAlias.new_alias(BitsAlias.REC);
    }
    @Override SB str(SB sb) {
      sb.p("@{").p(_alias);
      for( int i=0; i<_ids.length; i++ ) {
        sb.p(' ').p(_ids[i]).p(" = ");
        _flds[i].str(sb);
        if( i < _ids.length-1 ) sb.p(',');
      }
      return sb.p("}");
    }
    @Override SB p1(SB sb) { return sb.p("@{").p(_alias).p(" ... } "); }
    @Override SB p2(SB sb, VBitSet dups) {
      for( int i=0; i<_ids.length; i++ )
        _flds[i].p0(sb.i().p(_ids[i]).p(" = ").nl(),dups);
      return sb;
    }
    @Override boolean hm(Worklist work) {
      boolean progress = false;

      // Force result to be a struct with at least these fields.
      // Do not allocate a T2 unless we need to pick up fields.
      T2 rec = find();
      if( rec.is_err() ) return false;
      if( rec.is_leaf() ) {           // Must allocate.
        if( work==null ) return true; // Will progress
        T2[] t2s = new T2[_ids.length];
        for( int i=0; i<_ids.length; i++ )
          t2s[i] = _flds[i].find();
        T2.make_struct(false,BitsAlias.make0(_alias),_ids,t2s).unify(rec,work);
        rec=find();
        progress = true;
      }
      if( !rec.is_struct() ) throw unimpl();

      // Extra fields are unified with ERR since they are not created here:
      // error to load from a non-existing field
      if( rec._args != null )
        for( String id : rec._args.keySet() ) {
          if( Util.find(_ids,id)== -1 && !rec.arg(id).is_err() ) {
            if( work==null ) return true;
            progress |= rec.arg(id).unify(find().miss_field(id),work);
          }
        }

      // Unify existing fields.  Ignore extras on either side.
      for( int i=0; i<_ids.length; i++ ) {
        T2 fld = rec.arg(_ids[i]);
        if( fld!=null ) progress |= fld.unify(_flds[i].find(),work);
        if( work==null && progress ) return true;
      }
      rec.push_update(this);

      return progress;
    }
    @Override void add_hm_work(Worklist work) {
      work.push(_par);
      for( Syntax fld : _flds ) work.push(fld);
    }
    @Override Type val(Worklist work) {
      TypeFld[] flds = new TypeFld[_flds.length+1];
      flds[0] = TypeFld.NO_DISP;
      for( int i=0; i<_flds.length; i++ )
        flds[i+1] = TypeFld.make(_ids[i],_flds[i]._flow,Access.Final,ARG_IDX+i);
      TypeStruct tstr = TypeStruct.make(flds);
      TypeStruct t2 = tstr.approx(1,_alias);
      return TypeMemPtr.make(_alias,t2);
    }

    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par, nongen, work, T2.make_struct(false,BitsAlias.make0(_alias),null,null));
      int cnt = 1;              // One for self
      T2[] t2s = new T2[_ids.length];
      if( _ids.length!=0 ) _hmt._args = new NonBlockingHashMap<>();
      assert _hmt._deps==null;
      for( int i=0; i<_ids.length; i++ ) { // Prep all sub-fields
        cnt += _flds[i].prep_tree(this,nongen,work);
        t2s[i] = _flds[i].find();
        _hmt._args.put(_ids[i],t2s[i]);
      }
      return cnt;
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      for( Syntax fld : _flds )
        if( !fld.more_work(work) )
          return false;
      return true;
    }
  }

  // Field lookup in a Struct
  static class Field extends Syntax {
    final String _id;
    final Syntax _rec;
    Field( String id, Syntax str ) { _id=id; _rec =str; }
    @Override SB str(SB sb) { return _rec.str(sb).p(".").p(_id); }
    @Override SB p1 (SB sb) { return sb.p(".").p(_id); }
    @Override SB p2(SB sb, VBitSet dups) { return _rec.p0(sb,dups); }
    @Override boolean hm(Worklist work) {
      if( find().is_err() ) return false; // Already an error; no progress
      T2 rec = _rec.find();
      if( rec.is_nil() || (rec._flow!=null && rec._flow.must_nil()) )
        return find().unify(T2.make_err("May be nil when loading field "+_id),work);
      rec.push_update(this);
      T2 fld = rec.arg(_id);
      if( fld!=null )           // Unify against a pre-existing field
        return fld.unify(find(), work);
      // The remaining cases all make progress and return true
      if( work==null ) return true;
      if( rec.is_err() ) return find().unify(rec,work);
      // Not a struct or no field, force it to be one
      if( rec.is_struct() && rec.is_open() ) // Effectively unify with an extended struct.
        return rec.add_fld(_id,find(),work);
      if( rec.is_leaf() || rec.is_fun() )
        return T2.make_struct(true,BitsAlias.EMPTY,new String[]{_id}, new T2[]{find().push_update(rec._deps)}).unify(rec, work);
      // Closed record, field is missing
      return find().unify(rec.miss_field(_id),work);
    }
    @Override void add_hm_work(Worklist work) {
      work.push(_par);
      work.push(_rec);
      _rec.add_hm_work(work);
    }
    @Override Type val(Worklist work) {
      Type trec = _rec._flow;
      if( trec.above_center() ) return Type.XSCALAR;
      if( trec instanceof TypeMemPtr ) {
        TypeMemPtr tmp = (TypeMemPtr)trec;
        if( tmp._obj instanceof TypeStruct ) {
          TypeStruct tstr = (TypeStruct)tmp._obj;
          TypeFld fld = tstr.fld_find(_id);
          if( fld!=null ) return fld._t; // Field type
        }
        if( tmp._obj.above_center() ) return Type.XSCALAR;
      }
      // TODO: Need an error type here
      return Type.SCALAR;
    }
    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par, nongen, work, T2.make_leaf());
      return _rec.prep_tree(this,nongen,work)+1;
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      return _rec.more_work(work);
    }
  }


  abstract static class PrimSyn extends Lambda {
    static T2 BOOL, INT64, FLT64, STRP;
    static Worklist WORK;
    static int PAIR_ALIAS, TRIPLE_ALIAS;
    static void reset() {
      PAIR_ALIAS   = BitsAlias.new_alias(BitsAlias.REC);
      TRIPLE_ALIAS = BitsAlias.new_alias(BitsAlias.REC);
      BOOL  = T2.make_base(TypeInt.BOOL);
      INT64 = T2.make_base(TypeInt.INT64);
      FLT64 = T2.make_base(TypeFlt.FLT64);
      STRP  = T2.make_base(TypeMemPtr.STRPTR);
    }
    abstract String name();
    private static final String[][] IDS = new String[][] {
      null,
      {"x"},
      {"x","y"},
      {"x","y","z"},
    };
    PrimSyn(T2 ...t2s) {
      super(false,null,IDS[t2s.length-1]);
      _hmt = T2.make_fun(false, BitsFun.make0(_fidx), t2s).fresh();
      for( int i=0; i<_targs.length; i++ )
        _targs[i] = _hmt.arg(""+i).push_update(this);
    }
    abstract PrimSyn make();
    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par,nongen,work, _hmt);
      return 1;
    }
    @Override boolean hm(Worklist work) {
      T2 old = find();
      if( old.is_err() ) return false;
      assert old.is_fun();
      for( int i=0; i<_targs.length; i++ )
        if( targ(i).is_err() )
          return old.unify(targ(i),work);

      return false;
    }
    @Override void add_hm_work(Worklist work) {
      if( find().is_err() ) work.push(_par);
    }
    @Override void add_val_work(Syntax child, Worklist work) { throw unimpl(); }
    @Override boolean more_work(Worklist work) { return more_work_impl(work); }
    @Override SB str(SB sb){ return sb.p(name()); }
    @Override SB p1(SB sb) { return sb.p(name()); }
    @Override SB p2(SB sb, VBitSet dups){ return sb; }
  }


  // Pair
  static class Pair extends PrimSyn {
    @Override String name() { return "pair"; }
    static private T2 var1,var2;
    public Pair() {
      super(var1=T2.make_leaf(),var2=T2.make_leaf(),T2.make_struct(false,BitsAlias.make0(PAIR_ALIAS),new String[]{"0","1"},new T2[]{var1,var2}));
    }
    @Override PrimSyn make() { return new Pair(); }
    @Override Type apply(Syntax[] args) {
      TypeFld[] ts = new TypeFld[args.length+1];
      ts[0] = TypeFld.NO_DISP;  // Display
      for( int i=0; i<args.length; i++ ) ts[i+1] = TypeFld.make_tup(args[i]._flow,ARG_IDX+i);
      return TypeMemPtr.make(PAIR_ALIAS,TypeStruct.make(ts));
    }
  }


  // Triple
  static class Triple extends PrimSyn {
    @Override String name() { return "triple"; }
    static private T2 var1,var2,var3;
    public Triple() { super(var1=T2.make_leaf(),var2=T2.make_leaf(),var3=T2.make_leaf(),T2.make_struct(false,BitsAlias.make0(TRIPLE_ALIAS),new String[]{"0","1","2"},new T2[]{var1,var2,var3})); }
    @Override PrimSyn make() { return new Triple(); }
    @Override Type apply(Syntax[] args) {
      TypeFld[] ts = new TypeFld[args.length+1];
      ts[0] = TypeFld.NO_DISP;  // Display
      for( int i=0; i<args.length; i++ ) ts[i+1] = TypeFld.make_tup(args[i]._flow,ARG_IDX+i);
      return TypeMemPtr.make(TRIPLE_ALIAS,TypeStruct.make(ts));
    }
  }

  // Special form of a Lambda body for IF which changes the H-M rules.
  // None-executing paths do not unify args.
  static class If extends PrimSyn {
    @Override String name() { return "if"; }
    public If() { super(T2.make_leaf(),T2.make_leaf(),T2.make_leaf(),T2.make_leaf()); }
    @Override PrimSyn make() { return new If(); }
    @Override boolean hm(Worklist work) {
      T2 rez = find().arg("ret");
      // GCP helps HM: do not unify dead control paths
      if( DO_GCP ) {            // Doing GCP during HM
        Type pred = _types[0];
        if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
          return rez.unify(targ(2),work); // Unify only the false side
        if( pred.above_center() ) // Neither side executes
          return false;           // Unify neither side
        if( !pred.must_nil() )    // Unify only the true side
          return rez.unify(targ(1),work);
      }
      // Unify both sides with the result
      return
        rez       .unify(targ(1),work) |
        rez.find().unify(targ(2),work);
    }
    @Override Type apply( Syntax[] args) {
      Type pred= args[0]._flow;
      Type t1  = args[1]._flow;
      Type t2  = args[2]._flow;
      // Conditional Constant Propagation: only prop types from executable sides
      if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
        return t2;              // False only
      if( pred.above_center() ) // Delay any values
        return Type.XSCALAR;    // t1.join(t2);     // Join of either
      if( !pred.must_nil() )    // True only
        return t1;
      // Could be either, so meet
      return t1.meet(t2);
    }
  }

  // EQ
  static class EQ extends PrimSyn {
    @Override String name() { return "eq"; }
    static private T2 var1;
    public EQ() { super(var1=T2.make_leaf(),var1,BOOL); }
    @Override PrimSyn make() { return new EQ(); }
    @Override Type apply( Syntax[] args) {
      Type x0 = args[0]._flow;
      Type x1 = args[1]._flow;
      if( x0.above_center() || x1.above_center() ) return TypeInt.BOOL.dual();
      if( x0.is_con() && x1.is_con() && x0==x1 )
        return TypeInt.TRUE;
      // TODO: Can also know about nil/not-nil
      return TypeInt.BOOL;
    }
  }

  // EQ0
  static class EQ0 extends PrimSyn {
    @Override String name() { return "eq0"; }
    public EQ0() { super(INT64,BOOL); }
    @Override PrimSyn make() { return new EQ0(); }
    @Override Type apply( Syntax[] args) {
      Type pred = args[0]._flow;
      if( pred.above_center() ) return TypeInt.BOOL.dual();
      if( pred==Type.ALL ) return TypeInt.BOOL;
      if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
        return TypeInt.TRUE;
      if( pred.meet_nil(Type.NIL)!=pred )
        return TypeInt.FALSE;
      return TypeInt.BOOL;
    }
  }

  static class IsEmpty extends PrimSyn {
    @Override String name() { return "isempty"; }
    public IsEmpty() { super(STRP,BOOL); }
    @Override PrimSyn make() { return new IsEmpty(); }
    @Override Type apply( Syntax[] args) {
      Type pred = args[0]._flow;
      if( pred.above_center() ) return TypeInt.BOOL.dual();
      TypeObj to;
      if( pred instanceof TypeMemPtr && (to=((TypeMemPtr)pred)._obj) instanceof TypeStr && to.is_con() )
        return TypeInt.con(to.getstr().isEmpty() ? 1 : 0);
      return TypeInt.BOOL;
    }
  }

  // Remove a nil from a struct after a guarding if-test
  static class NotNil extends PrimSyn {
    @Override String name() { return " notnil"; }
    public NotNil() { super(T2.make_leaf(),T2.make_leaf()); }
    @Override PrimSyn make() { return new NotNil(); }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      int cnt = super.prep_tree(par,nongen,work);
      find().arg("ret").push_update(this);
      return cnt;
    }
    @Override boolean hm(Worklist work) {
      T2 arg = targ(0);
      if( arg.is_err() ) return false; // Already an error
      T2 fun = find(); assert fun.is_fun();
      T2 ret = fun.arg("ret");
      // If the arg is already nil-checked, can be a nilable of a nilable.
      if( arg==ret ) return false;
      // Already an expanded nilable
      if( arg.is_nil() && arg.arg("?") == ret ) return false;
      // Already an expanded nilable with base
      if( arg.is_base() && ret.is_base() ) {
        assert !arg.is_open() && !ret.is_open();
        if( arg._flow == ret._flow.meet_nil(Type.XNIL) ) return false;
        if( work==null ) return true;
        Type mt = arg._flow.meet(ret._flow);
        Type rflow = mt.join(Type.NSCALR);
        Type aflow = mt.meet_nil(Type.XNIL);
        if( rflow != ret._flow ) { ret._flow=rflow; work.push(_par); }
        if( aflow != arg._flow ) { arg._flow=aflow; work.push(_par); }
        return true;
      }
      // Already an expanded nilable with struct
      if( arg.is_struct() && ret.is_struct() && arg._flow == arg._flow.meet_nil(Type.XNIL) ) {
        // But cannot just check the aliases, since they may not match.
        // Also check that the fields align
        boolean progress=false;
        for( String fld : arg._args.keySet() ) {
          if( arg.arg(fld) != ret.arg(fld) )
            { progress=true; break; } // Field/HMtypes misaligned
        }
        if( !progress && arg.is_open() )
          for( String fld : ret._args.keySet() )
            if( arg.arg(fld)==null )
              { progress=true; break; }
        if( !progress ) return false; // No progress
      }
      if( work==null ) return true;
      // If the arg is already nil-checked, can be a nilable of a nilable.
      if( arg.is_nil() && ret.is_nil() )
        return arg.unify(ret,work);
      // Unify with arg with a nilable version of the ret.
      return T2.make_nil(ret).find().unify(arg,work);
    }
    @Override Type apply( Syntax[] args) {
      Type val = args[0]._flow;
      if( val==Type.XNIL ) return Type.XSCALAR; // Weird case of not-nil nil
      return val.join(Type.NSCALR);
    }
  }

  // multiply
  static class Mul extends PrimSyn {
    @Override String name() { return "*"; }
    public Mul() { super(INT64,INT64,INT64); }
    @Override PrimSyn make() { return new Mul(); }
    @Override Type apply( Syntax[] args) {
      Type t0 = args[0]._flow;
      Type t1 = args[1]._flow;
      if( t0.above_center() || t1.above_center() )
        return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t1 instanceof TypeInt ) {
        if( t0.is_con() && t0.getl()==0 ) return TypeInt.ZERO;
        if( t1.is_con() && t1.getl()==0 ) return TypeInt.ZERO;
        if( t0.is_con() && t1.is_con() )
          return TypeInt.con(t0.getl()*t1.getl());
      }
      return TypeInt.INT64;
    }
  }

  // add integers
  static class Add extends PrimSyn {
    @Override String name() { return "+"; }
    public Add() { super(INT64,INT64,INT64); }
    @Override PrimSyn make() { return new Add(); }
    @Override Type apply( Syntax[] args) {
      Type t0 = args[0]._flow;
      Type t1 = args[1]._flow;
      if( t0.above_center() || t1.above_center() )
        return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t1 instanceof TypeInt ) {
        if( t0.is_con() && t1.is_con() )
          return TypeInt.con(t0.getl()+t1.getl());
      }
      return TypeInt.INT64;
    }
  }

  // decrement
  static class Dec extends PrimSyn {
    @Override String name() { return "dec"; }
    public Dec() { super(INT64,INT64); }
    @Override PrimSyn make() { return new Dec(); }
    @Override Type apply( Syntax[] args) {
      Type t0 = args[0]._flow;
      if( t0.above_center() ) return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t0.is_con() )
        return TypeInt.con(t0.getl()-1);
      return TypeInt.INT64;
    }
  }

  // int->str
  static class Str extends PrimSyn {
    @Override String name() { return "str"; }
    public Str() { super(INT64,STRP); }
    @Override PrimSyn make() { return new Str(); }
    @Override Type apply( Syntax[] args) {
      Type i = args[0]._flow;
      if( i.above_center() ) return TypeMemPtr.STRPTR.dual();
      if( i instanceof TypeInt && i.is_con() )
        return TypeMemPtr.make(BitsAlias.STRBITS,TypeStr.con(String.valueOf(i.getl()).intern()));
      return TypeMemPtr.STRPTR;
    }
  }


  // flt->(factor flt flt)
  static class Factor extends PrimSyn {
    @Override String name() { return "factor"; }
    public Factor() { super(FLT64,FLT64); }
    @Override PrimSyn make() { return new Factor(); }
    @Override Type apply( Syntax[] args) {
      Type flt = args[0]._flow;
      if( flt.above_center() ) return TypeFlt.FLT64.dual();
      return TypeFlt.FLT64;
    }
  }

  // ---------------------------------------------------------------------
  // T2 types form a Lattice, with 'unify' same as 'meet'.  T2's form a DAG
  // (cycles if i allow recursive unification) with sharing.  Each Syntax has a
  // T2, and the forest of T2s can share.  Leaves of a T2 can be either a
  // simple concrete base type, or a sharable leaf.  Unify is structural, and
  // where not unifyable the union is replaced with an Error.
  static class T2 {
    private static int CNT=0;
    final int _uid;

    // A plain type variable starts with a 'V', and can unify directly.
    // Everything else is structural - during unification they must match names
    // and arguments and Type constants.
    @NotNull String _name; // name, e.g. "->" or "pair" or "V123" or "base"

    // Structural parts to unify with, or null.
    // If Leaf or Error, then null.
    // If unified, contains the single key ">>".
    // If Base   , contains the single key "Base".
    // If Nil    , contains the single key "?"
    // If Lambda , contains keys "0","1","2" for args or "ret" for return.
    // If Apply  , contains keys "fun" and "0","1","2" for args
    // If Struct , contains keys for the field labels.  No display.  Is null if no fields.
    NonBlockingHashMap<String,T2> _args;

    // A dataflow type or null.
    // If Leaf, or unified or Nil or Apply, then null.
    // If Base, then the flow type.
    // If Lambda, then a TFP and the BitsFun   matters.
    // If Struct, then a TMP and the BitsAlias matters.
    // If Error, then a TypeStr with the error (not a TMP to a TS).
    Type _flow;

    boolean _open; // Structs allow more fields.  Not quite the same as TypeStruct._open field.

    // Theory: when positive polarity and negative polarity, bases need to widen.
    boolean _is_func_input;

    // Dependent (non-local) tvars to revisit
    Ary<Syntax> _deps;

    // Constructor factories.
    static T2 make_leaf() { return new T2("V",null,null,false); }
    static T2 make_nil (T2 leaf) { return new T2("?",null,new NonBlockingHashMap<String,T2>(){{put("?",leaf);}},false); }
    static T2 make_base(Type flow) { assert !(flow instanceof TypeStruct); return new T2("Base",flow,null,false); }
    static T2 make_fun( boolean is_func_input, BitsFun fidxs, T2... t2s ) {
      NonBlockingHashMap<String,T2> args = new NonBlockingHashMap<>();
      for( int i=0; i<t2s.length-1; i++ ) {
        T2 t2 = t2s[i];
        args.put("" + i, t2);
        if( is_func_input )  t2.widen_bases();
      }
      args.put("ret",t2s[t2s.length-1]);
      return new T2("->",TypeFunPtr.make(fidxs,t2s.length-1,Type.ANY),args,false);
    }
    // A struct with fields
    static T2 make_struct( boolean open, BitsAlias aliases, String[] ids, T2[] flds ) {
      NonBlockingHashMap<String,T2> args = ids==null ? null : new NonBlockingHashMap<>();
      if( ids!=null )
        for( int i=0; i<ids.length; i++ )
          args.put(ids[i],flds[i]);
      Type flow = TypeMemPtr.make(aliases,TypeStruct.make("",false,false));
      return new T2("@{}",flow,args,open);
    }
    static T2 make_err(String s) { return new T2("Err",TypeStr.con(s.intern()),null,false); }
    T2 miss_field(String id) { return make_err("Missing field "+id+" in "+p()); }
    @SuppressWarnings("unchecked")
    T2 copy() {
      T2 t = new T2(_name,_flow,null,_open);
      if( _args!=null ) t._args = (NonBlockingHashMap<String,T2>)_args.clone();
      t._deps  = _deps;
      t._is_func_input = _is_func_input;
      return t;
    }

    private T2(@NotNull String name, Type flow, NonBlockingHashMap<String,T2> args, boolean open) {
      _uid  = CNT++;
      _name = name;
      _args = args;
      _flow = flow;
      _open = open;
    }

    // A type var, not a concrete leaf.
    boolean isa(String name) { return Util.eq(_name,name); }
    boolean is_leaf() { return isa("V"); }
    boolean unified() { return isa("X"); }
    boolean is_base()  { return isa("Base"); }
    boolean is_nil()   { return isa("?"   ); }
    boolean is_fun ()  { return isa("->"  ); }
    boolean is_struct(){ return isa("@{}" ); }
    boolean is_err()   { return isa("Err" ); }
    boolean is_open()  { return _open; }
    int size() { return _args==null ? 0 : _args.size(); }

    T2 debug_find() {// Find, without the roll-up
      if( !unified() ) return this; // Shortcut
      if( _args==null ) return this;
      T2 u = _args.get(">>");
      if( !u.unified() ) return u;  // Shortcut
      // U-F search, no fixup
      while( u.unified() ) u = u.arg(">>");
      return u;
    }

    T2 find() {
      T2 u = _find0();
      return u.is_nil() ? u._find_nil() : u;
    }
    // U-F find
    private T2 _find0() {
      T2 u = debug_find();
      if( u==this ) return u;
      if( u==_args.get(">>") ) return u;
      // UF fixup
      T2 v = this, v2;
      while( (v2=v._args.get(">>"))!=u ) { v._args.put(">>",u); v = v2; }
      return u;
    }
    // Nilable fixup.  nil-of-leaf is OK.  nil-of-anything-else folds into a
    // nilable version of the anything-else.
    @SuppressWarnings("unchecked")
    private T2 _find_nil() {
      T2 n = arg("?");
      if( n.is_leaf() ) return this;
      // Nested nilable-and-not-leaf, need to fixup the nilable
      if( n.is_base() || n.is_struct() ) {
        _flow = n._flow.meet_nil(Type.XNIL);
        _open = n._open;
        assert !_is_func_input || n._is_func_input;
        _args = n._args==null ? null : (NonBlockingHashMap<String, T2>)n._args.clone();  // Shallow copy the TV2 fields
        _name = n._name;
      } else if( n.is_nil() ) {
        _args.put("?",n.arg("?"));
      } else
        throw unimpl();

      n.merge_deps(this);
      return this;
    }
    // U-F find on the args collection
    T2 arg( String key) {
      if( _args==null ) return null;
      T2 u = _args.get(key);
      if( u==null ) return null;
      T2 uu = u.find();
      if( u!=uu ) _args.put(key,uu);
      return uu;
    }

    // Recursively build a conservative flow type from an HM type.  The HM
    // is_struct wants to be a TypeMemPtr, but the recursive builder is built
    // around TypeStruct.

    // No function arguments, just function returns.
    static final NonBlockingHashMapLong<TypeStruct> ADUPS = new NonBlockingHashMapLong<>();
    Type as_flow() {
      assert Type.intern_check();
      assert ADUPS.isEmpty();
      Type t = _as_flow();
      ADUPS.clear();
      assert Type.intern_check();
      return t;
    }
    Type _as_flow() {
      assert !unified();
      if( is_base() ) return _flow;
      if( is_leaf() ) return Type.SCALAR;
      if( is_err()  ) return _flow;
      if( is_fun()  ) return TypeFunPtr.make(((TypeFunPtr)_flow)._fidxs,_args.size()-1,Type.ANY);
      if( is_nil() ) return Type.SCALAR;
      if( is_struct() ) {
        TypeStruct tstr = ADUPS.get(_uid);
        if( tstr==null ) {
          Type.RECURSIVE_MEET++;
          tstr = TypeStruct.malloc("",false,false).add_fld(TypeFld.NO_DISP);
          if( _args!=null )
            for( String id : _args.keySet() )
              tstr.add_fld(TypeFld.malloc(id));
          tstr.set_hash();
          ADUPS.put(_uid,tstr); // Stop cycles
          if( _args!=null )
            for( String id : _args.keySet() )
              tstr.fld_find(id).setX(arg(id)._as_flow()); // Recursive
          if( --Type.RECURSIVE_MEET == 0 )
            // Shrink / remove cycle dups.  Might make new (smaller)
            // TypeStructs, so keep RECURSIVE_MEET enabled.
            tstr = tstr.install();
        } else {
          tstr._cyclic=true;    // Been there, done that, just mark it cyclic
        }
        return ((TypeMemPtr)_flow).make_from(tstr);
      }

      throw unimpl();
    }


    // U-F union; this becomes that; returns 'that'.
    // No change if only testing, and reports progress.
    boolean union(T2 that, Worklist work) {
      assert !unified() && !that.unified(); // Cannot union twice
      if( this==that ) return false;
      if( work==null ) return true; // Report progress without changing

      // Keep the merge of all base types, and add _deps.
      if( !that.is_err() ) {
        if( that._flow==null ) that._flow = _flow;
        else if( _flow!=null ) {
          if( _flow.getClass()!=that._flow.getClass() )
            return union_err(that,work,"Cannot unify "+this.p()+" and "+that.p());
          that._flow = _flow.meet(that._flow);
          that._open &= _open;
        }
      }
      if( _is_func_input ) that.widen_bases();

      // Work all the deps
      that.add_deps_work(work);
      this.add_deps_work(work);      // Any progress, revisit deps
      // Hard union this into that, no more testing.
      return _union(that);
    }

    // Hard unify this into that, no testing for progress.
    private boolean _union( T2 that ) {
      assert !unified() && !that.unified(); // Cannot union twice
      // Worklist: put updates on the worklist for revisiting
      merge_deps(that);    // Merge update lists, for future unions
      // Kill extra information, to prevent accidentally using it
      _deps = null;
      _flow=null;
      _open=false;
      _is_func_input = false;
      _args = new NonBlockingHashMap<String,T2>(){{put(">>",that);}};
      _name = "X";             // Flag as a unified
      assert unified();
      return true;
    }

    // U-F union; this is nilable and becomes that.
    // No change if only testing, and reports progress.
    boolean unify_nil(T2 that, Worklist work) {
      assert is_nil() && !that.is_nil();
      if( work==null ) return true; // Will make progress
      // Clone the top-level struct and make this nilable point to the clone;
      // this will collapse into the clone at the next find() call.
      // Unify the nilable leaf into that.
      T2 leaf = arg("?");  assert leaf.is_leaf();
      T2 copy = that.copy();
      if( that.is_base() || that.is_struct() ) {
        copy._flow = copy._flow.join(Type.NSCALR);
      } else
        throw unimpl();
      leaf.add_deps_work(work);
      return leaf.union(copy,work) | that._union(find());
    }

    // -----------------
    // Structural unification.
    // Returns false if no-change, true for change.
    // If work is null, does not actually change anything, just reports progress.
    // If work and change, unifies 'this' into 'that' (changing both), and
    // updates the worklist.
    static private final HashMap<Long,T2> DUPS = new HashMap<>();
    boolean unify( T2 that, Worklist work ) {
      if( this==that ) return false;
      assert DUPS.isEmpty();
      boolean progress = _unify(that,work);
      DUPS.clear();
      return progress;
    }

    // Structural unification, 'this' into 'that'.  No change if just testing
    // (work is null) and returns a progress flag.  If updating, both 'this'
    // and 'that' are the same afterwards.
    private boolean _unify(T2 that, Worklist work) {
      assert !unified() && !that.unified();
      if( this==that ) return false;

      // All leaf types immediately unify.
      if( this._args==null && that._args==null ) {
        T2 lhs=this, rhs=that;
        if( is_err() ||         // Errors beat all others
            (!that.is_err() && is_base()) )
          { rhs=this; lhs=that; } // Base beats plain leaf
        // If tied, keep lower uid
        if( Util.eq(lhs._name,rhs._name) && _uid<that._uid ) { rhs=this; lhs=that; }
        return lhs.union(rhs,work);
      }
      // Any leaf immediately unifies with any non-leaf
      if( this.is_leaf() || that.is_err() ) return this.union(that,work);
      if( that.is_leaf() || this.is_err() ) return that.union(this,work);
      // Special case for nilable union something
      if( this.is_nil() && !that.is_nil() ) return this.unify_nil(that,work);
      if( that.is_nil() && !this.is_nil() ) return that.unify_nil(this,work);

      // Cycle check
      long luid = dbl_uid(that);    // long-unique-id formed from this and that
      T2 rez = DUPS.get(luid);
      assert rez==null || rez==that;
      if( rez!=null ) return false; // Been there, done that
      DUPS.put(luid,that);          // Close cycles

      if( work==null ) return true; // Here we definitely make progress; bail out early if just testing

      if( !Util.eq(_name,that._name) )
        return union_err(that,work,"Cannot unify "+this.p()+" and "+that.p());
      assert _args!=that._args; // Not expecting to share _args and not 'this'

      // Structural recursion unification.
      T2 thsi = this;
      for( String key : thsi._args.keySet() ) {
        T2 vthis = thsi.arg(key);
        T2 vthat = that.arg(key);
        if( vthat==null ) {
          if( that.is_open() ) that.add_fld(key,vthis,work);
        } else vthis._unify(vthat,work); // Matching fields unify
        if( (thsi=thsi.find()).is_err() ) break;
        if( (that=that.find()).is_err() ) break;
      }
      // Fields on the RHS are aligned with the LHS also
      if( !thsi.is_err() && !that.is_err() )
        for( String key : that._args.keySet() )
          if( thsi.arg(key)==null )
            if( thsi.is_open() )  this.add_fld(key,that.arg(key),work); // Add to LHS: todo: need a find() here
            else                  that.del_fld(key, work);              // Drop from RHS

      if( that.debug_find() != that ) throw unimpl();
      if( thsi.is_err() && !that.is_err() )
        return that._union(thsi); // Preserve error on LHS
      return thsi.union(that,work);
    }

    // Insert a new field; keep fields sorted
    private boolean add_fld(String id, T2 fld, Worklist work) {
      assert is_struct();
      if( _args==null ) {
        _args = new NonBlockingHashMap<>();
        fld.push_update(_deps);
      }
      _args.put(id,fld);
      add_deps_work(work);
      return true;
    }
    // Delete a field
    private boolean del_fld( String id, Worklist work) {
      assert is_struct();
      add_deps_work(work);
      _args.remove(id);
      if( _args.size()==0 ) _args=null;
      return true;
    }

    private long dbl_uid(T2 t) { return dbl_uid(t._uid); }
    private long dbl_uid(long uid) { return ((long)_uid<<32)|uid; }

    private boolean union_err(T2 that, Worklist work, String msg) {
      union(make_err(msg),work);
      return that.union(find(),work);
    }

    // -----------------
    // Make a (lazy) fresh copy of 'this' and unify it with 'that'.  This is
    // the same as calling 'fresh' then 'unify', without the clone of 'this'.
    // Returns progress.
    // If work is null, we are testing only and make no changes.
    static private final HashMap<T2,T2> VARS = new HashMap<>();
    boolean fresh_unify(T2 that, VStack nongen, Worklist work) {
      assert VARS.isEmpty() && DUPS.isEmpty();
      int old = CNT;
      boolean progress = _fresh_unify(that,nongen,work);
      VARS.clear();  DUPS.clear();
      if( work==null && old!=CNT && DEBUG_LEAKS )
        throw unimpl("busted, made T2s but just testing");
      return progress;
    }

    // Outer recursive version, wraps a VARS check around other work
    private boolean _fresh_unify(T2 that, VStack nongen, Worklist work) {
      assert !unified() && !that.unified();
      // Check for cycles
      T2 prior = VARS.get(this);
      if( prior!=null )         // Been there, done that
        return prior.find()._unify(that,work);  // Also, 'prior' needs unification with 'that'
      // Check for equals
      if( cycle_equals(that) ) return vput(that,false);

      if( that.is_err() ) return vput(that,false); // That is an error, ignore 'this' and no progress
      if( this.is_err() ) return vput(that,_unify(that,work));

      // In the non-generative set, so do a hard unify, not a fresh-unify.
      if( nongen_in(nongen) ) return vput(that,_unify(that,work)); // Famous 'occurs-check', switch to the normal unify

      // LHS leaf, RHS is unchanged but goes in the VARS
      if( this.is_leaf() ) return vput(that,false);
      if( that.is_leaf() )  // RHS is a tvar; union with a deep copy of LHS
        return work==null || vput(that,that.union(_fresh(nongen),work));

      // Bases MEET cons in RHS
      if( is_base() && that.is_base() ) {
        assert !_open && !that._open;
        Type mt = _flow.meet(that._flow);
        if( mt==that._flow ) return vput(that,false);
        if( work == null ) return true;
        that._flow = mt;
        return vput(that,true);
      }

      // Special handling for nilable
      if( this.is_nil() && !that.is_nil() ) {
        Type mt = that._flow.meet_nil(Type.XNIL);
        if( mt==that._flow ) return false; // Nilable already
        if( work!=null ) that._flow = mt;
        return true;
      }
      // That is nilable and this is not
      if( that.is_nil() && !this.is_nil() ) {
        assert is_base() || is_struct();
        if( work==null ) return true;
        T2 copy = this;
        if( _flow.must_nil() ) { // Make a not-nil version
          copy = copy();
          copy._flow = _flow.join(Type.NSCALR);
          if( _args!=null ) throw unimpl(); // shallow copy
        }
        boolean progress = copy._fresh_unify(that.arg("?"),nongen,work);
        return _flow.must_nil() ? vput(that,progress) : progress;
      }

      if( !Util.eq(_name,that._name) )
        return work == null || vput(that,that._unify(make_err("Cannot unify "+this.p()+" and "+that.p()),work));

      // Both same (probably both nil)
      if( _args==that._args ) return vput(that,false);

      // Structural recursion unification, lazy on LHS
      boolean progress = vput(that,false); // Early set, to stop cycles
      boolean missing = size()!= that.size();
      for( String key : _args.keySet() ) {
        T2 lhs = this.arg(key);
        T2 rhs = that.arg(key);
        if( rhs==null ) {         // No RHS to unify against
          missing = true;         // Might be missing RHS
          if( that.is_open() ) {  // If RHS is open, copy field into it
            if( work==null ) return true; // Will definitely make progress
            progress |= that.add_fld(key,lhs._fresh(nongen), work);
          } else if( this.is_open() ) {
            if( work==null ) return true; // Will definitely make progress
            T2 t2 = that.miss_field(key);
            progress |= lhs._fresh_unify(t2,nongen,work) | that.add_fld(key,t2,work);
          }
        } else {
          progress |= lhs._fresh_unify(rhs,nongen,work);
        }
        if( (that=that.find()).is_err() ) return true;
        if( progress && work==null ) return true;
      }
      // Fields in RHS and not the LHS are also merged; if the LHS is open we'd
      // just copy the missing fields into it, then unify the structs (shortcut:
      // just skip the copy).  If the LHS is closed, then the extra RHS fields
      // are removed.
      if( missing && !is_open() && that._args!=null )
        for( String id : that._args.keySet() ) // For all fields in RHS
          if( arg(id)==null ) {                // Missing in LHS
            if( !that.arg(id).is_err() ) {
              if( work == null ) return true;    // Will definitely make progress
              progress |= that.del_fld(id,work);
            }
          }
      // Meet open flags and aliases
      if( _flow!=null )
        that._flow = _flow.meet(that._flow);
      that._open &= _open;
      return progress;
    }
    private boolean vput(T2 that, boolean progress) { VARS.put(this,that); return progress; }

    // Return a fresh copy of 'this'
    T2 fresh() {
      assert VARS.isEmpty();
      T2 rez = _fresh(null);
      VARS.clear();
      return rez;
    }
    private T2 _fresh(VStack nongen) {
      assert !unified();
      T2 rez = VARS.get(this);
      if( rez!=null ) return rez; // Been there, done that
      // Unlike the original algorithm, to handle cycles here we stop making a
      // copy if it appears at this level in the nongen set.  Otherwise, we'd
      // clone it down to the leaves - and keep all the nongen leaves.
      // Stopping here preserves the cyclic structure instead of unrolling it.
      if( nongen_in(nongen) ) {
        VARS.put(this,this);
        return this;
      }

      // Structure is deep-replicated
      T2 t = copy();
      if( is_leaf() ) t._deps=null;
      VARS.put(this,t);         // Stop cyclic structure looping
      if( _args!=null )
        for( String key : _args.keySet() )
          t._args.put(key, arg(key)._fresh(nongen));
      return t;
    }

    // -----------------
    private static final VBitSet ODUPS = new VBitSet();
    boolean occurs_in_type(T2 x) {
      ODUPS.clear();
      return _occurs_in_type(x);
    }

    boolean _occurs_in_type(T2 x) {
      assert !unified() && !x.unified();
      if( x==this ) return true;
      if( ODUPS.tset(x._uid) ) return false; // Been there, done that
      if( !x.is_leaf() && x._args!=null )
        for( String key : x._args.keySet() )
          if( _occurs_in_type(x.arg(key)) )
            return true;
      return false;
    }

    boolean nongen_in(VStack vs) {
      if( vs==null ) return false;
      ODUPS.clear();
      for( T2 t2 : vs )
        if( _occurs_in_type(t2.find()) )
          return true;
      return false;
    }

    // -----------------
    // Test for structural equivalence, including cycles
    static private final HashMap<T2,T2> CDUPS = new HashMap<>();
    boolean cycle_equals(T2 t) {
      assert CDUPS.isEmpty();
      boolean rez = _cycle_equals(t);
      CDUPS.clear();
      return rez;
    }
    boolean _cycle_equals(T2 t) {
      assert !unified() && !t.unified();
      if( this==t ) return true;
      if( _flow !=t._flow ) return false;         // Base-cases have to be completely identical
      if( !Util.eq(_name,t._name) ) return false; // Wrong type-var names
      if( is_leaf() ) return false;               // Two leaves must be the same leaf, already checked for above
      if( size() != t.size() ) return false;      // Mismatched sizes
      if( _args==t._args ) return true;           // Same arrays (generally both null)
      // Cycles stall the equal/unequal decision until we see a difference.
      T2 tc = CDUPS.get(this);
      if( tc!=null )  return tc==t; // Cycle check; true if both cycling the same
      CDUPS.put(this,t);
      for( String key : _args.keySet() ) {
        T2 arg = t.arg(key);
        if( arg==null || !arg(key)._cycle_equals(arg) )
          return false;
      }
      return true;
    }

    // -----------------
    // Walk a T2 and a matching flow-type, and build a map from T2 to flow-types.
    // Stop if either side loses corresponding structure.  This operation must be
    // monotonic because the result is JOINd with GCP types.
    Type walk_types_in(Type t) {
      long duid = dbl_uid(t._uid);
      if( Apply.WDUPS.putIfAbsent(duid,TypeStruct.ALLSTRUCT)!=null ) return t;
      assert !unified();
      if( is_err() ) return fput(Type.SCALAR); //
      // Base variables (when widened to an HM type) might force a lift.
      if( is_base() ) return fput(_flow);
      // Free variables keep the input flow type.
      if( is_leaf() ) return fput(t);
      // Nilable
      if( is_nil() )
        return arg("?").walk_types_in(fput(t.join(Type.NSCALR)));
      if( is_fun() ) {
        if( !(t instanceof TypeFunPtr) ) return t; // Typically, some kind of error situation
        fput(t);                // Recursive types put themselves first
        TypeFunPtr tfp = (TypeFunPtr)t;
        T2 ret = arg("ret");
        if( tfp._fidxs.test(1) ) return t; // External unknown function, returns the worst
        if( tfp._fidxs == BitsFun.EMPTY ) return t; // Internal, unknown function
        for( int fidx : ((TypeFunPtr)t)._fidxs ) {
          Lambda lambda = Lambda.FUNS.get(fidx);
          Type body = lambda.find().is_err()
            ? Type.SCALAR           // Error, no lift
            : (lambda._body == null // Null only for primitives
               ? lambda.find().arg("ret").as_flow() // Get primitive return type
               : lambda._body._flow); // Else use body type
          ret.walk_types_in(body);
        }
        return t;
      }

      if( is_struct() ) {
        fput(t);                // Recursive types need to put themselves first
        if( !(t instanceof TypeMemPtr) ) return t;
        TypeMemPtr tmp = (TypeMemPtr)t;
        TypeStruct ts = (TypeStruct)tmp._obj; // Always a TypeStruct here
        if( _args!=null )
          for( String id : _args.keySet() ) {
            TypeFld fld = ts.fld_find(id);
            arg(id).walk_types_in(fld==null ? Type.XSCALAR : fld._t);
          }
        return ts;
      }

      throw unimpl();
    }
    private Type fput(final Type t) {
      Apply.T2MAP.merge(this, t, Type::meet);
      return t;
    }

    // Walk an Apply output flow type, and attempt to replace parts of it with
    // stronger flow types from the matching input types.
    Type walk_types_out( Type t, Apply apply ) {
      assert !unified();
      if( t == Type.XSCALAR ) return t;  // No lift possible
      Type tmap = Apply.T2MAP.get(this); // Output HM type has a matching input HM type has a matching input flow type
      if( is_leaf() || is_err() ) { // If never mapped on input, leaf is unbound by input
        if( tmap==null || !tmap.isa(t) ) return t;
        push_update(apply);     // Re-run apply if this leaf re-maps
        return tmap;
      }
      if( is_base() ) return tmap==null ? _flow : tmap.join(t);
      if( is_nil() ) return t.join(Type.NSCALR); // nil is a function wrapping a leaf which is not-nil
      if( is_fun() ) return t; // No change, already known as a function (and no TFS in the flow types)
      if( is_struct() ) {
        if( !(t instanceof TypeMemPtr) )
          return tmap == null ? as_flow().join(t) : tmap;  // The most struct-like thing you can be
        TypeMemPtr tmp = (TypeMemPtr)t;
        TypeStruct ts0 = (TypeStruct)tmp._obj;
        TypeStruct ts = Apply.WDUPS.get(_uid);
        if( ts != null ) ts._cyclic = true;
        else {
          Type.RECURSIVE_MEET++;
          ts = TypeStruct.malloc("",false,false);
          for( TypeFld fld : ts0.flds() ) ts.add_fld(fld.malloc_from());
          ts.set_hash();
          Apply.WDUPS.put(_uid,ts); // Stop cycles
          for( TypeFld fld : ts.flds() )
            if( arg(fld._fld) != null )
              fld.setX(arg(fld._fld).walk_types_out(fld._t,apply));
          if( --Type.RECURSIVE_MEET == 0 )
            // Shrink / remove cycle dups.  Might make new (smaller)
            // TypeStructs, so keep RECURSIVE_MEET enabled.
            ts = ts.install();
        }
        return tmp.make_from(ts);
      }
      throw unimpl();           // Handled all cases
    }

    // -----------------
    private static final VBitSet FIDX_VISIT  = new VBitSet();
    // Apply a pre-walk predicate to all elements of a type graph.
    // If the predicate is true, walk the subparts, if false then skip.
    // Return the true if the predicate is anywhere true.
    void walk( BiPredicate<T2,String> action) {
      assert FIDX_VISIT.isEmpty();
      _walk(action,null);
      FIDX_VISIT.clear();
    }
    private void _walk( BiPredicate<T2,String> action, String key) {
      assert !unified();
      if( FIDX_VISIT.tset(_uid) ) return;
      if( !action.test(this,key) ) return;
      if( _args!=null )
        for( String arg : _args.keySet() )
          arg(arg)._walk(action,arg);
    }

    // Widen all reachable bases on function inputs
    private void widen_bases() {
      walk((e, key) -> {
          if( Util.eq(key,"ret") ) return false; // Early exit
          e._is_func_input=true;
          if( e.is_base() ) e._flow = e._flow.widen();
          return true;
        });
    }


    // -----------------
    // This is a T2 function that is the target of 'fresh', i.e., this function
    // might be fresh-unified with some other function.  Push the application
    // down the function parts; if any changes the fresh-application may make
    // progress.
    static final VBitSet UPDATE_VISIT  = new VBitSet();
    T2 push_update( Ary<Syntax> as ) { if( as != null ) for( Syntax a : as ) push_update(a);  return this;   }
    T2 push_update( Syntax a) { assert UPDATE_VISIT.isEmpty(); push_update_impl(a); UPDATE_VISIT.clear(); return this; }
    private void push_update_impl(Syntax a) {
      assert !unified();
      if( UPDATE_VISIT.tset(_uid) ) return;
      if( _deps==null ) _deps = new Ary<>(Syntax.class);
      if( _deps.find(a)==-1 ) _deps.push(a);
      if( _args != null )
        for( T2 t2 : _args.values() )
          t2.debug_find().push_update_impl(a);
    }

    // Recursively add-deps to worklist
    void add_deps_work( Worklist work ) { assert UPDATE_VISIT.isEmpty(); add_deps_work_impl(work); UPDATE_VISIT.clear(); }
    private void add_deps_work_impl( Worklist work ) {
      work.addAll(_deps);
      if( UPDATE_VISIT.tset(_uid) ) return;
      if( _args != null )
        for( T2 t2 : _args.values() )
          t2.add_deps_work_impl(work);
    }

    // Merge this._deps into that
    void merge_deps( T2 that ) {
      if( _deps != null )
        that.push_update(_deps);
    }


    // -----------------
    // Glorious Printing

    // Look for dups, in a tree or even a forest (which Syntax.p() does)
    public VBitSet get_dups() { return _get_dups(new VBitSet(),new VBitSet()); }
    public VBitSet _get_dups(VBitSet visit, VBitSet dups) {
      if( visit.tset(_uid) ) {
        dups.set(debug_find()._uid);
      } else {
        if( _args!=null )
          for( T2 t : _args.values() )
            t._get_dups(visit,dups);
      }
      return dups;
    }

    @Override public String toString() { return str(new SB(), new VBitSet(), get_dups(), true ).toString(); }
    public String p() { VCNT=0; VNAMES.clear(); return str(new SB(), new VBitSet(), get_dups(), false ).toString(); }
    private static int VCNT;
    private static final HashMap<T2,String> VNAMES = new HashMap<>();

    // Fancy print for Debuggers - includes explicit U-F re-direction.
    // Does NOT roll-up U-F, has no side-effects.
    SB str(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      boolean dup = dups.get(_uid);
      if( !debug && unified() ) return find().str(sb,visit,dups,debug);
      if( unified() || is_leaf() ) {
        vname(sb,debug);
        return unified() ? _args.get(">>").str(sb.p(">>"), visit, dups, debug) : sb;
      }
      if( is_err () )  return sb.p(_flow.getstr());
      if( is_base() )  return sb.p(_flow);

      if( dup ) vname(sb,debug);
      if( visit.tset(_uid) && dup ) return sb;
      if( dup ) sb.p(':');

      // Special printing for functions
      if( is_fun() ) {
        if( debug )
          if( _flow==null ) sb.p("[?]"); // Should always have an alias
          else {
            if( _flow instanceof TypeFunPtr ) ((TypeFunPtr)_flow)._fidxs.clear(0).str(sb);
            else _flow.str(sb,visit,null,debug); // Weirdo type printing
          }
        sb.p("{ ");
        for( String fld : sorted_flds() )
          if( !Util.eq("ret",fld) )
            str0(sb,visit,_args.get(fld),dups,debug).p(' ');
        return str0(sb.p("-> "),visit,_args.get("ret"),dups,debug).p(" }");
      }

      // Special printing for structures
      if( is_struct() ) {
        if( is_prim() ) return sb.p("@{PRIMS}");
        final boolean is_tup = is_tup(); // Distinguish tuple from struct during printing
        if( debug )
          if( _flow==null ) sb.p("[?]"); // Should always have an alias
          else {
            if( _flow instanceof TypeMemPtr ) ((TypeMemPtr)_flow)._aliases.clear(0).str(sb);
            else _flow.str(sb,visit,null,debug); // Weirdo type printing
          }
        sb.p(is_tup ? "(" : "@{");
        if( _args==null ) sb.p(" ");
        else {
          for( String fld : sorted_flds() )
            // Skip field names in a tuple
            str0(is_tup ? sb.p(' ') : sb.p(' ').p(fld).p(" = "),visit,_args.get(fld),dups,debug).p(is_tup ? ',' : ';');
        }
        if( is_open() ) sb.p(" ...,");
        if( _args!=null && _args.size() > 0 ) sb.unchar();
        sb.p(!is_tup ? "}" : ")");
        if( _flow!=null && _flow.must_nil() ) sb.p("?");
        return sb;
      }

      if( is_nil() )
        return str0(sb,visit,arg("?"),dups,debug).p('?');

      // Generic structural T2
      sb.p("(").p(_name).p(" ");
      if( _args!=null )
        for( String s : _args.keySet() )
          str0(sb.p(s).p(':'),visit,_args.get(s),dups,debug).p(" ");
      return sb.unchar().p(")");
    }
    static private SB str0(SB sb, VBitSet visit, T2 t, VBitSet dups, boolean debug) { return t==null ? sb.p("_") : t.str(sb,visit,dups,debug); }
    private void vname( SB sb, boolean debug) {
      final boolean vuid = debug && (unified()||is_leaf());
      sb.p(VNAMES.computeIfAbsent(this, (k -> vuid ? (_name + k._uid) : ((++VCNT) - 1 + 'A' < 'V' ? ("" + (char) ('A' + VCNT - 1)) : ("V" + VCNT)))));
    }
    private boolean is_tup() { return _args==null || _args.isEmpty() || _args.containsKey("0"); }
    private Collection<String> sorted_flds() { return new TreeMap<>(_args).keySet(); }
    boolean is_prim() {
      return is_struct() && _args!=null && _args.containsKey("!");
    }

    // Debugging tool
    T2 find(int uid) { return _find(uid,new VBitSet()); }
    private T2 _find(int uid, VBitSet visit) {
      if( visit.tset(_uid) ) return null;
      if( _uid==uid ) return this;
      if( _args==null ) return null;
      for( T2 arg : _args.values() )
        if( (arg=arg._find(uid,visit)) != null )
          return arg;
      return null;
    }
    static void reset() { CNT=0; DUPS.clear(); VARS.clear(); ODUPS.clear(); CDUPS.clear(); ADUPS.clear(); UPDATE_VISIT.clear(); }
  }
}
