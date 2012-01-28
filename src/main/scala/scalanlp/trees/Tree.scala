package scalanlp.trees;
/*
 Copyright 2010 David Hall

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/


import scala.collection.mutable.ArrayBuffer;
import scalanlp.serialization.DataSerialization
import scalanlp.serialization.DataSerialization._
import java.io.{StringReader, DataInput, DataOutput}
import scalanlp.util.Lens
;

class Tree[+L](val label: L, val children: IndexedSeq[Tree[L]])(val span: Span) {
  def isLeaf = children.size == 0;
  /**
  * A tree is valid if this' span contains all children's spans 
  * and each child abuts the next one.
  */
  def isValid = isLeaf || {
    children.map(_.span).forall(this.span contains _) &&
    children.iterator.drop(1).zip(children.iterator).forall { case (next,prev) =>
      prev.span.end == next.span.start
    } &&
    children(0).span.start == this.span.start && 
    children.last.span.end == this.span.end
  }

  def leaves:Iterable[Tree[L]] = if(isLeaf) {
    IndexedSeq(this).view
  } else  {
    children.map(_.leaves).foldLeft[Stream[Tree[L]]](Stream.empty){_ append _}
  }

  /**
   * Useful for stripping the words out of a tree
   * Returns (tree without leaves, leaves)
   */
  def cutLeaves: (Tree[L],IndexedSeq[L]) = {
    def recCutLeaves(tree: Tree[L]): (Option[Tree[L]],IndexedSeq[L]) = {
      if(tree.isLeaf) (None,IndexedSeq(tree.label))
      else {
        val fromChildren = tree.children.map(recCutLeaves _)
        Some(Tree(tree.label,fromChildren.flatMap(_._1))(span)) -> fromChildren.flatMap(_._2)
      }
    }
    val (treeOpt,leaves) = recCutLeaves(this)
    treeOpt.get -> leaves
  }


  def map[M](f: L=>M):Tree[M] = Tree( f(label), children map { _ map f})(span);

  def allChildren = preorder;

  def preorder: Iterator[Tree[L]] = {
    children.map(_.preorder).foldLeft( Iterator(this)) { _ ++ _ }
  }

  def postorder: Iterator[Tree[L]] = {
    children.map(_.postorder).foldRight(Iterator(this)){_ ++ _}
  }

  def leftHeight:Int = if(isLeaf) 0 else 1 + children(0).leftHeight


  import Tree._;
  override def toString = recursiveToString(this,0,new StringBuilder).toString;
  def render[W](words: Seq[W], newline: Boolean = true) = recursiveRender(this,0,words, newline, new StringBuilder).toString;
}

object Tree {
  def apply[L](label: L, children: IndexedSeq[Tree[L]])(span: Span) = new Tree(label,children)(span);
  def unapply[L](t: Tree[L]): Option[(L,IndexedSeq[Tree[L]])] = Some((t.label,t.children));
  def fromString(input: String):(Tree[String],Seq[String]) = new PennTreeReader(new StringReader(input)).next

  private def recursiveToString[L](tree: Tree[L], depth: Int, sb: StringBuilder):StringBuilder = {
    import tree._;
    sb append "( " append tree.label append " [" append span.start append "," append span.end append "] ";
    for( c <- tree.children ) {
      recursiveToString(c,depth+1,sb) append " ";
    }
    sb append ")";
    sb
  }


  private def recursiveRender[L,W](tree: Tree[L], depth: Int, words: Seq[W], newline: Boolean, sb: StringBuilder): StringBuilder =  {
    import tree._;
    if(newline) sb append "\n" append "  " * depth;
    else sb.append(" ");
    sb append "(" append tree.label
    if(isLeaf) {
      sb append span.map(words).mkString(" "," ","");
    } else {
      //sb append "\n"
      for( c <- children ) {
        recursiveRender(c,depth+1,words,newline, sb);
      }
    }
    sb append ")";
    sb
  }

  implicit def treeSerializationReadWritable[L:ReadWritable]: ReadWritable[Tree[L]] = new ReadWritable[Tree[L]] {
    def write(data: DataOutput, t: Tree[L]) = {
      implicitly[ReadWritable[L]].write(data,t.label);
      DataSerialization.write(data,t.children);
      data.writeInt(t.span.start);
      data.writeInt(t.span.end);
    }
    def read(data: DataInput) = {
      val label = implicitly[ReadWritable[L]].read(data);
      val children = indexedSeqReadWritable(this).read(data);
      val begin = data.readInt();
      val end = data.readInt();
      new Tree(label,children)(Span(begin,end));
    }
  }

}

sealed trait BinarizedTree[+L] extends Tree[L] {
  override def map[M](f: L=>M): BinarizedTree[M] = null; 
  def extend[B](f: BinarizedTree[L]=>B):BinarizedTree[B]
  def relabelRoot[B>:L](f: L=>B):BinarizedTree[B]
}

case class BinaryTree[+L](l: L,
                          leftChild: BinarizedTree[L],
                          rightChild: BinarizedTree[L])(span: Span
                        ) extends Tree[L](l,IndexedSeq(leftChild,rightChild))(span
                        ) with BinarizedTree[L] {
  override def map[M](f: L=>M):BinaryTree[M] = BinaryTree( f(label), leftChild map f, rightChild map f)(span);
  override def extend[B](f: BinarizedTree[L]=>B) = BinaryTree( f(this), leftChild extend f, rightChild extend f)(span);
  def relabelRoot[B>:L](f: L=>B):BinarizedTree[B] = BinaryTree(f(label), leftChild,rightChild)(span)

}

case class UnaryTree[+L](l: L, child: BinarizedTree[L])(span: Span
                        ) extends Tree[L](l,IndexedSeq(child))(span
                        ) with BinarizedTree[L] {
  override def map[M](f: L=>M): UnaryTree[M] = UnaryTree( f(label), child map f)(span);
  override def extend[B](f: BinarizedTree[L]=>B) = UnaryTree( f(this), child extend f)(span);
  def relabelRoot[B>:L](f: L=>B):BinarizedTree[B] = UnaryTree(f(label), child)(span)
}

case class NullaryTree[+L](l: L)(span: Span) extends Tree[L](l,IndexedSeq.empty)(span) with BinarizedTree[L]{
  override def map[M](f: L=>M): NullaryTree[M] = NullaryTree( f(label))(span);
  override def extend[B](f: BinarizedTree[L]=>B) = NullaryTree( f(this))(span);
  def relabelRoot[B>:L](f: L=>B):BinarizedTree[B] = NullaryTree(f(label))(span)
}

object Trees {
  def binarize[L](tree: Tree[L], relabel: (L,L)=>L, left:Boolean=false):BinarizedTree[L] = tree match {
    case Tree(l, Seq()) => NullaryTree(l)(tree.span)
    case Tree(l, Seq(oneChild)) => UnaryTree(l,binarize(oneChild,relabel))(tree.span);
    case Tree(l, Seq(leftChild,rightChild)) => 
      BinaryTree(l,binarize(leftChild,relabel),binarize(rightChild,relabel))(tree.span);
    case Tree(l, Seq(leftChild, otherChildren@ _*)) if left =>
      val newLeftChild = binarize(leftChild,relabel);
      val newRightLabel = relabel(l,leftChild.label);
      val newRightChildSpan = Span(newLeftChild.span.end,tree.span.end);
      val newRightChild = binarize(Tree(newRightLabel,otherChildren.toIndexedSeq)(newRightChildSpan), relabel);
      BinaryTree(l, newLeftChild, newRightChild)(tree.span)
    case Tree(l, children) => // right binarization
      val newRightChild = binarize(children.last,relabel);
      val newLeftLabel = relabel(l,newRightChild.label);
      val newLeftChildSpan = Span(tree.span.start,newRightChild.span.start)
      val newLeftChild = binarize(Tree(newLeftLabel,children.take(children.length-1))(newLeftChildSpan), relabel);
      BinaryTree(l, newLeftChild, newRightChild)(tree.span)
  }

  def deannotate(tree: Tree[String]):Tree[String] = tree.map(deannotateLabel _)
  def deannotate(tree: BinarizedTree[String]):BinarizedTree[String] = tree.map(deannotateLabel _)
  def deannotateLabel(l: String) = l.takeWhile(c => c != '^' && c != '>')

  def markovizeBinarization(tree: BinarizedTree[String], order: Int):BinarizedTree[String] = {
    tree.map{ l =>
      val headIndex = l.indexOf('>')
      if(headIndex < 0) l
      else {
        val head = l.slice(0,headIndex+1);
        // find the n'th from the back.
        var i = l.length-1;
        var n = 0;
        while(i >= 0 && n < order) {
          if(l.charAt(i) == '_') n+=1;
          i-=1;
        }
        if(i <= headIndex) l
        else head + l.substring(i+1);
      }
    }
  }

  /**
   * Adds horizontal markovization to an already binarized tree with no markovization
   */
  def addHorizontalMarkovization[T](tree: BinarizedTree[T],
                                    order: Int,
                                    join: (T,Seq[Either[T,T]])=>T,
                                    isIntermediate: T=>Boolean):BinarizedTree[T] = {
    def rec(tree: BinarizedTree[T],history: List[Either[T,T]] = List.empty):BinarizedTree[T] = {
      val newLabel = if(isIntermediate(tree.label)) join(tree.label,history.take(order)) else tree.label
      tree match {
        case BinaryTree(_, t1, t2) =>
          val newHistory = if(isIntermediate(tree.label)) history.take(order) else List.empty
          BinaryTree(newLabel, rec(t1,Right(t2.label) :: newHistory), rec(t2,Left(t1.label)::newHistory))(tree.span)
        case UnaryTree(label, child) =>
          UnaryTree(newLabel,rec(child,if(child.label == label) history else List.empty))(tree.span)
        case NullaryTree(_) =>
          NullaryTree(newLabel)(tree.span)
      }

    }

    rec(tree)
  }

  def addHorizontalMarkovization(tree: BinarizedTree[String], order: Int):BinarizedTree[String] = {
    def join(t: String, chain: Seq[Either[String,String]]) = chain.map{ case Left(l) => "\\" + l case Right(r) => "/" + r}.mkString(t +">","_","")
    addHorizontalMarkovization(tree,order,join,(_:String).startsWith("@"))
  }

  private def stringBinarizer(currentLabel: String, append: String) = {
    val head = if(currentLabel(0) != '@') '@' + currentLabel + ">" else currentLabel
    val r = head + "_" + append
    r
  }
  def binarize(tree: Tree[String]):BinarizedTree[String] = binarize[String](tree, stringBinarizer _ );

  def debinarize[L](tree: Tree[L], isBinarized: L=>Boolean):Tree[L] = {
    val l = tree.label;
    val children = tree.children;
    val buf = new ArrayBuffer[Tree[L]];
    for(c <- children) {
      if(isBinarized(c.label)) {
        buf ++= debinarize(c,isBinarized).children;
      } else {
        buf += debinarize(c,isBinarized);
      }
    }
    Tree(l,buf)(tree.span);
  }

  def debinarize(tree: Tree[String]):Tree[String] = debinarize(tree, (x:String) => x.startsWith("@"));

  def binarizeProjection(s: String) = {
    var end = s.indexOf(">")-1
    val endThingy = s.indexOf("^");
    if(end < 0) end = s.length;
    if(endThingy < end && endThingy >= 0) end = endThingy;
    s.slice(0,end);
  }

  private def xbarStringBinarizer(currentLabel: String, append:String) = {
    if(currentLabel.startsWith("@")) currentLabel
    else "@" + currentLabel
  }
  def xBarBinarize(tree: Tree[String], left: Boolean = false) = binarize[String](tree,xbarStringBinarizer, left);

  def annotateParents[L](tree: Tree[L], join: (L,L)=>L, depth: Int, history: List[L] = List.empty):Tree[L] = {
    if(depth == 0) tree
    else {
      val newLabel = (tree.label :: history).iterator.take(depth).reduceLeft(join);
      new Tree(newLabel,tree.children.map(c => annotateParents[L](c,join,depth,tree.label :: history.take(depth-1 max 0))))(tree.span);
    }
  }

  def annotateParents(tree: Tree[String], depth: Int):Tree[String] = annotateParents(tree,{(x:String,b:String)=>x + '^' + b},depth);

  def annotateParentsBinarized[L](tree: BinarizedTree[L], join: (L,L)=>L, keepParent: L=>Boolean, depth: Int):BinarizedTree[L] = {
    def rec(tree: BinarizedTree[L], history: List[L] = List.empty):BinarizedTree[L] = {
      tree match {
        //invariant: history is the (depth) non-intermediate symbols, where we remove unary-identity transitions
        case BinaryTree(label, t1, t2) =>
          val newLabel = if(keepParent(label)) history.take(depth-1).foldLeft(label)(join) else history.drop(1).foldLeft(label)(join)
          val newHistory = if(keepParent(label)) (label :: history) take depth else history
          val lchild = rec(t1,newHistory)
          val rchild = rec(t2,newHistory)
          BinaryTree(newLabel, lchild, rchild)(tree.span)
        case UnaryTree(label, child) =>
          val newLabel = if(keepParent(label)) history.take(depth-1).foldLeft(label)(join) else history.drop(1).foldLeft(label)(join)
          val newHistory = if(keepParent(label) && label != child.label) (label :: history) take depth else history
          UnaryTree(newLabel,rec(child,newHistory))(tree.span)
        case NullaryTree(label) =>
          val newLabel = if(history.head == label) history.reduceLeft(join) else history.take(depth-1).foldLeft(label)(join)
          NullaryTree(newLabel)(tree.span)
      }
    }
    rec(tree)

  }

  def annotateParentsBinarized(tree: BinarizedTree[String], depth: Int):BinarizedTree[String] = {
    annotateParentsBinarized(tree,{(x:String,b:String)=>x + '^' + b},!(_:String).startsWith("@"),depth)
  };

  object Transforms {

    class EmptyNodeStripper[T](implicit lens: Lens[T,String]) extends (Tree[T]=>Option[Tree[T]]) {
      def apply(tree: Tree[T]):Option[Tree[T]] = {
        if(lens.get(tree.label) == "-NONE-") None
        else if(tree.span.start == tree.span.end) None // screw stupid spans
        else {
          val newC = tree.children map this filter (None!=)
          if(newC.length == 0 && !tree.isLeaf) None
          else Some(Tree(tree.label,newC map (_.get))(tree.span))
        }
      }
    }

    class XOverXRemover[L] extends (Tree[L]=>Tree[L]) {
      def apply(tree: Tree[L]):Tree[L] = {
        if(tree.children.size == 1 && tree.label == tree.children(0).label) {
          this(tree.children(0));
        } else {
          Tree(tree.label,tree.children.map(this))(tree.span);
        }
      }
    }

    class FunctionNodeStripper[T](implicit lens: Lens[T,String]) extends (Tree[T]=>Tree[T]) {
      def apply(tree: Tree[T]): Tree[T] = {
        tree.map{ label =>
          lens.get(label) match {
          case "-RCB-" | "-RRB-" | "-LRB-" | "-LCB-" => label
          case x =>
            if(x.startsWith("--")) lens.set(label,x.replaceAll("---.*","--"))
            else lens.set(label,x.replaceAll("[-|=].*",""))
          }
        }
      }
    }


    object StandardStringTransform extends (Tree[String]=>Tree[String]) {
      private val ens = new EmptyNodeStripper[String];
      private val xox = new XOverXRemover[String];
      private val fns = new FunctionNodeStripper[String];
      def apply(tree: Tree[String]): Tree[String] = {
        xox(fns(ens(tree).get)) map (_.intern);
      }
    }

    class LensedStandardTransform[T](implicit lens: Lens[T,String]) extends (Tree[T]=>Tree[T]) {
      private val ens = new EmptyNodeStripper[T]
      private val xox = new XOverXRemover[T]
      private val fns = new FunctionNodeStripper[T]

      def apply(tree: Tree[T]) = {
        xox(fns(ens(tree).get)) map ( l => lens.set(l,lens.get(l).intern));
      }
    }

    object GermanTreebankTransform extends (Tree[String]=>Tree[String]) {
      private val ens = new EmptyNodeStripper;
      private val xox = new XOverXRemover[String];
      private val fns = new FunctionNodeStripper;
      private val tr = GermanTraceRemover;
      def apply(tree: Tree[String]): Tree[String] = {
        xox(tr(fns(ens(tree).get))) map (_.intern);
      }
    }

    object GermanTraceRemover extends (Tree[String]=>Tree[String]) {
      def apply(tree: Tree[String]):Tree[String] = {
        tree.map(_.replaceAll("\\-\\*T.\\*",""))
      }
    }

  }
}
