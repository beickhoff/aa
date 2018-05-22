package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.Bits;
import com.cliffc.aa.util.SB;

import java.util.HashMap;

// FunNode is a RegionNode; args point to all the known callers.  Zero slot is
// null, same as a C2 Region.  Args 1+ point to callers; Arg 1 points to Scope
// as the generic unknown worse-case caller; This can be removed once no more
// callers can appear after parsing.  Each unique call-site to a function gets
// a new path to the FunNode.
//
// FunNodes are finite in count and can be unique densely numbered.
//
// Pointing to the FunNode are ParmNodes which are also PhiNodes; one input
// path for each known caller.  Zero slot points to the FunNode.  Arg1 points
// to the generic unknown worse-case caller (a type-specific ConNode with
// worse-case legit bottom type).  The collection of these ConNodes can share
// TypeVars to structurally type the inputs.  ParmNodes merge all input path
// types (since they are a Phi), and the call "loses precision" for type
// inference there.
//
// The function body points to the FunNode and ParmNodes like C2.
//
// RetNode is different from C2, to support precise function type inference.
// Rets point to the return control and the original FunNode; its type is a
// control Tuple, similar to IfNodes.  Pointing to the RetNode are Projs with
// call-site indices; they carry the control-out of the function for their
// call-site.  While there is a single Ret for all call-sites, Calls can "peek
// through" to see the function body learning the incoming args come from a
// known input path.
// 
// Results come from CastNodes, which up-cast the merged function results back
// to the call-site specific type - this is where the precision loss are
// ParmNodes is regained.  CastNodes point to the original call-site control
// path and the function result.

// Example: FunNode "map" is called with type args A[] and A->B and returns
// type B[]; at one site its "int[]" and "int->str" and at another site its
// "flt[]" and "flt->int" args.  The RetNode merges results to be "SCALAR[]".
// The Cast#1 upcasts its value to "str[]", and Cast#2 upcasts its value to
// "int[]".
// 
//  0 Scope...               -- Call site#1 control
//  1 Con_TV#C (really A[] ) -- Call site#1 arg#1
//  2 Con_TV#D (really A->B) -- Call site#1 arg#2
//  3 Ctrl callsite 2        -- Call site#2 control
//  4 arg#int[]              -- Call site#2 arg#1  
//  5 arg#int->str           -- Call site#2 arg#2  
//  6 Ctrl callsite 3        -- Call site#3 control
//  7 arg#flt[]              -- Call site#3 arg#1  
//  8 arg#flt->int           -- Call site#3 arg#2  
//  9 Fun _ 0 3 6
// 10 ParmX 9 1 4 7  -- meet of A[] , int[]   , flt[]
// 11 ParmX 9 2 5 8  -- meet of A->B, int->str, flt->int
// -- function body, uses 9 10 11
// 12 function body control
// 13 function body return value
// -- function ends
// 14 Ret 12 13 9 {A[] {A -> B} -> B[]}
// 15 Proj#1 14            -- Return path  for unknown caller in slot 1
// 16 Cast#1 0 13#SCALAR[] -- Return value for unknown caller in slot 1
// 16 Proj#2 14            -- Return path  for caller {int[] {int -> str} -> str[]}
// 17 Cast#2 3 13#flt[]    -- Return value for caller {int[] {int -> str} -> str[]}
// 18 Proj#3 14            -- Return path  for caller {flt[] {flt -> int} -> int[]}
// 19 Cast#3 6 13#int[]    -- Return value for caller {flt[] {flt -> int} -> int[]}
//
// The Parser will use the Env to point to the RetNode to create more callers
// as parsing continues.  The RetNode is what is passed about for a "function
// pointer".
//
// Keep a global function table, indexed by _fidx.  Points to generic ProjNode
// caller as currently does... but nothing else does.  Parser gets a function
// constant with _fidx as a number and makes a ConNode.  Unresolved takes in
// these ConNodes.  CallNode does the _fidx lookup when inlining the call.
//
public class FunNode extends RegionNode {
  private static int CNT=1; // Function index; -1 for 'all' and 0 for 'any'
  public final TypeFun _tf; // Worse-case correct type
  private final byte _op_prec;// Operator precedence; only set top-level primitive wrappers
  public FunNode(Node scope, PrimNode prim) {
    this(scope,TypeFun.make(prim._targs,prim._ret,CNT++),prim.op_prec(),prim._name);
  }
  private FunNode(Node scope, TypeTuple ts, Type ret, String name) {
    this(scope,TypeFun.make(ts,ret,CNT++),-1,name);
  }
  public FunNode(Node scope, String name) { // Used to forward-decl anon functions
    this(scope,TypeFun.make(TypeTuple.ALL,Type.SCALAR,CNT++),-1,name);
  }
  public FunNode(int nargs, Node scope) { this(scope,TypeFun.any(nargs,CNT++),-1,null); }
  private FunNode(Node scope, TypeFun tf, int op_prec, String name) {
    super(OP_FUN,scope);
    _tf = tf;
    _op_prec = (byte)op_prec;
    bind(name,tf.fidx());
  }
  private int fidx() { return _tf._fidxs.getbit(); }
  public void init(ProjNode proj) {
    throw AA.unimpl();
    //FUNS.set_def(fidx(),proj);
  }
  private static int PRIM_CNT;
  public static void init0() { PRIM_CNT=CNT; }
  public static void reset_to_init0(GVNGCM gvn) { CNT = PRIM_CNT; NAMES.set_len(PRIM_CNT); }
  public static void clear_forward_ref(Type t, GVNGCM gvn) {
    assert t.forward_ref();
    int fidx = ((TypeFun)t).fidx();
    //// The FunNode points to the current scope; when deleting the FunNode do
    //// NOT also delete the current scope, even if this is the last use.
    //FUNS.at(fidx).at(0).at(2).pop();
    //FUNS.set_def(fidx,null,gvn);
    throw AA.unimpl();
  }
  public static ProjNode get(int fidx) {
    throw AA.unimpl();
    //return (ProjNode)FUNS.at(fidx);
  }
  @Override String xstr() { return _tf.toString(); }
  @Override String str() { return names(_tf._fidxs,new SB()).toString(); }
  // Debug only: make an attempt to bind name to a function
  private static Ary<String> NAMES = new Ary<>(new String[1],0);
  public static void bind(String tok, int fidx) {
    assert NAMES.atX(fidx)==null; // Attempt to double-bind
    NAMES.setX(fidx,tok);
  }
  // Can return nothing, or "name" or "{name0,name1,name2...}"
  public static SB names(Bits fidxs, SB sb ) {
    int fidx = fidxs.abit();
    if( fidx > 0 ) return name(fidx,sb);
    sb.p('{');
    int cnt=0;
    for( Integer ii : fidxs ) {
      if( ++cnt==3 ) break;
      name(ii,sb).p(',');
    }
    if( cnt>=3 || fidxs.abit() < 0 ) sb.p("...");
    sb.p('}');
    return sb;
  }
  private static SB name( int i, SB sb ) {
    String name = NAMES.atX(i);
    return sb.p(name==null?Integer.toString(i):name);
  }
  public String name() { return NAMES.at(_tf._fidxs.getbit()); }

  // FunNodes can "discover" callers if the function constant exists in the
  // program anywhere (since, during execution (or optimizations) it may arrive
  // at a CallNode and initiate a new call to the function).  Until all callers
  // are accounted for, the FunNode keeps slot1 reserved with the most
  // conservative allowed arguments, under the assumption a as-yet-detected
  // caller will call with such arguments.  This is a quick check to detect
  // may-have-more-callers.  
  boolean has_unknown_callers(GVNGCM gvn) {
    Node funcon = gvn.con(_tf);
    for( Node use : funcon._uses ) {
      if( use instanceof ScopeNode ) return true;
      throw AA.unimpl();
    }
    return false;
  }

  
  // ----
  @Override public Node ideal(GVNGCM gvn) {
    Node n = split_callers(gvn);
    if( n != null ) return n;
    
    // Else generic Region ideal
    return ideal(gvn,has_unknown_callers(gvn));
  }

  private Node split_callers( GVNGCM gvn ) {
    // Bail if there are any dead paths; RegionNode ideal will clean out
    for( int i=1; i<_defs._len; i++ ) if( gvn.type(at(i))==TypeErr.ANY ) return null;
    if( _defs._len <= 2 ) return null; // No need to split callers if only 1

    // Gather the ParmNodes and the RetNode.  Ignore other (control) uses
    int nargs = _tf._ts._ts.length;
    ParmNode[] parms = new ParmNode[nargs];
    RetNode ret = null;
    for( Node use : _uses )
      if( use instanceof ParmNode ) parms[((ParmNode)use)._idx] = (ParmNode)use;
      else if( use instanceof RetNode ) { assert ret==null || ret==use; ret = (RetNode)use; }

    // Find the Proj matching the call to-be-cloned
    CProjNode[] projs = new CProjNode[_defs._len];
    for( Node use : ret._uses )
      if( use instanceof CProjNode )
        projs[((CProjNode)use)._idx] = (CProjNode)use;
    // Bail if there are any dead paths; RetNode ideal will clean out
    if( projs[1] == null ) return null;
    for( int i=2; i<_defs._len; i++ ) {
      if( projs[i] == null ) return null;
      if( projs[i]._uses._len!=2 ) return null; // should be exactly a control-use and a cast-use
    }

    // Make a clone of the original function to split the callers.  Done for
    // e.g. primitive type-specialization or for tiny functions.
    FunNode fun =   split_callers_heuristic(gvn,parms,ret,projs);
    return fun==null ? null : split_callers(gvn,parms,ret,projs,fun);
  }
  
  // General heuristic for splitting the many callers of this function into
  // groups with a private function body.  Can be split to refine types
  // (e.g. primitive int math vs primitive float math), or to allow
  // constant-prop for some args, or for tiny size.
  private FunNode split_callers_heuristic( GVNGCM gvn, ParmNode[] parms, RetNode ret, ProjNode[] projs ) {
    // Split for tiny body
    FunNode fun0 = split_size(gvn,parms,ret,projs);
    if( fun0 != null ) return fun0;

    // Split for primitive type specialization
    FunNode fun1 = type_special(gvn,parms,ret,projs);
    if( fun1 != null ) return fun1;

    return null;                // No splitting callers
  }

  private FunNode split_size( GVNGCM gvn, ParmNode[] parms, RetNode ret, ProjNode[] projs ) {
    Node rez = ret.at(1);
    if( !(rez instanceof ParmNode && rez.at(0) == this) ) { // Zero-op body
      // Else check for 1-op body
      for( Node parm : rez._defs )
        if( parm != null && parm != this &&
            !(parm instanceof ParmNode && parm.at(0) == this) &&
            !(parm instanceof ConNode) )
          return null;
    }
    // Make a prototype new function header.  No generic unknown caller
    // in slot 1, only slot 2.
    //FunNode fun = new FunNode(gvn.con(TypeErr.ANY),_tf._ts,_tf._ret,_name);
    //fun.add_def(at(2));
    //return fun;
    throw AA.unimpl();
  }

  // Look for type-specialization inlining.  If any ParmNode has an unresolved
  // Call user, then we'd like to make a clone of the function body (at least
  // up to getting all the function TypeUnions to clear out).  The specialized
  // code uses generalized versions of the arguments, where we only specialize
  // on arguments that help immediately.
  private FunNode type_special( GVNGCM gvn, ParmNode[] parms, RetNode ret, ProjNode[] projs ) {
    // Visit all ParmNodes, looking for unresolved call uses
    boolean any_unr=false;
    for( ParmNode parm : parms )
      for( Node call : parm._uses )
        if( call instanceof CallNode &&
            (gvn.type(call.at(1)) instanceof TypeUnion || // Call overload not resolved
             gvn.type(call      ) instanceof TypeErr ) ) {// Call result is an error (arg mismatch)
          any_unr = true; break;
        }
    if( !any_unr ) return null; // No unresolved calls; no point in type-specialization

    // TODO: Split with a known caller in slot 1
    if( !(at(1) instanceof ScopeNode) )
      return null; // Untested: Slot 1 is not the generic unparsed caller

    // If Parm has unresolved calls, we want to type-specialize on its
    // arguments.  Call-site #1 is the most generic call site for the parser
    // (all Scalar args).  Peel out 2nd call-site args and generalize them.
    Type[] sig = new Type[parms.length];
    for( int i=0; i<parms.length; i++ )
      sig[i] = gvn.type(parms[i].at(2)).widen();
    // Make a new function header with new signature
    TypeTuple ts = TypeTuple.make(sig);
    assert ts.isa(_tf._ts);
    if( ts == _tf._ts ) return null; // No improvement for further splitting
    // Make a prototype new function header.  Clone the generic unknown caller in slot 1.  
    //FunNode fun = new FunNode(at(1),ts,_tf._ret,_name);
    //// Look at remaining paths and decide if they split or stay
    //for( int j=2; j<projs.length; j++ ) {
    //  boolean split=true;
    //  for( int i=0; i<parms.length; i++ )
    //    split &= gvn.type(parms[i].at(j)).widen().isa(sig[i]);
    //  fun.add_def(split ? at(j) : gvn.con(TypeErr.ANY));
    //}
    //return fun;
    throw AA.unimpl();
  }

  // Clone the function body, and split the callers of 'this' into 2 sets; one
  // for the old and one for the new body.  The new function may have a more
  // refined signature, and perhaps no unknown callers.  
  private Node split_callers(GVNGCM gvn, ParmNode[] parms, RetNode ret, ProjNode[] projs, FunNode fun) {
    // Clone the function body
    HashMap<Node,Node> map = new HashMap<>();
    Ary<Node> work = new Ary<>(new Node[1],0);
    map.put(this,fun);
    work.addAll(_uses);         // Prime worklist
    while( work._len > 0 ) {    // While have work
      Node n = work.pop();      // Get work
      if( map.get(n) != null ) continue; // Already visited?
      if( n instanceof CastNode && n.at(0).at(0)==ret )
        continue;              // Do not clone function data-exit 
      if( n != ret )           // Except for the Ret, control-exit for function
        work.addAll(n._uses);  // Visit all uses also
      map.put(n,n.copy()); // Make a blank copy with no edges and map from old to new
    }

    // TODO: Split with a known caller in slot 1
    if( !(at(1) instanceof ScopeNode) )  throw AA.unimpl(); // Untested: Slot 1 is not the generic unparsed caller

    // Fill in edges.  New Nodes point to New instead of Old; everybody
    // shares old nodes not in the function (and not cloned).  The
    // FunNode & Parms only get the matching slice of args.
    Node any = gvn.con(TypeErr.ANY);
    for( Node n : map.keySet() ) {
      Node c = map.get(n);
      if( n instanceof ParmNode && n.at(0) == this ) {  // Leading edge ParmNodes
        c.add_def(map.get(n.at(0))); // Control
        c.add_def(gvn.con(fun._tf._ts._ts[((ParmNode)n)._idx])); // Generic arg#1
        for( int j=2; j<projs.length; j++ ) // Get the new parm path or null according to split
          c.add_def( fun.at(j)==any ? any : n.at(j) );
      } else if( n != this ) {  // Interior nodes
        for( Node def : n._defs ) {
          Node newdef = map.get(def);
          c.add_def(newdef==null ? def : newdef);
        }
      }
    }
    // Kill split-out path-ins to the old code
    for( int j=2; j<projs.length; j++ )
      if( fun.at(j)!=any )  // Path split out?
        set_def(j,any,gvn); // Kill incoming path on old FunNode
    // The final control-out has exactly 2 uses: a control-use and the return
    // result data-use.  The data-use needs repointing to the new body.
    for( int j=2; j<projs.length; j++ ) {
      ProjNode proj = projs[j];
      assert proj._uses._len==2;
      if( fun.at(j)!=any )  { // Path split out?
        CastNode data = (CastNode) ((proj._uses.at(0) instanceof CastNode) ? proj._uses.at(0) : proj._uses.at(1));
        Node newdata = map.get(ret.at(1));
        gvn.set_def_reg(proj,0,map.get(ret)); // Repoint proj as well
        gvn.set_def_reg(data,1,newdata);
      }
    }

    // Put all new nodes into the GVN tables and worklists
    for( Node c : map.values() ) gvn.rereg(c);
    // TODO: Hook with proper signature into ScopeNode under an Unresolved.
    // Future calls may resolve to either the old version or the new.
    fun.init(null); // TODO: hook a generic ProjNode for future calls to resolve against
    return this;
  }

  @Override public int hashCode() { return OP_FUN+_tf.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof FunNode) ) return false;
    FunNode fun = (FunNode)o;
    return _tf==fun._tf;
  }
  @Override public byte op_prec() { return _op_prec; }
}
