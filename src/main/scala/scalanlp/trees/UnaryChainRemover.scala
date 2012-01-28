package scalanlp.trees

import collection.mutable.ArrayBuffer

import UnaryChainRemover._
import scalala.tensor.mutable.Counter2
import scalala.tensor.::

/**
 * Removes unaries chains A -> B -> ... -> C, replacing them with A -> C and remembering the most likely
 * chain for any A C pair.
 *
 * @author dlwh
 */
class UnaryChainRemover[L](trans: L=>L = identity[L]_) {
  def removeUnaryChains[W](trees: Iterator[(BinarizedTree[L],Seq[W])]):(IndexedSeq[(BinarizedTree[L],Seq[W])],ChainReplacer[L]) = {
    val buf = new ArrayBuffer[(BinarizedTree[L],Seq[W])];
    val counts = Counter2[(L,L),Seq[L],Int];

    def transform(t: BinarizedTree[L],parentWasUnary:Boolean):BinarizedTree[L] = t match {
      case UnaryTree(l,c) =>
        val (chain,cn) = stripChain(c);
        counts(trans(l) -> trans(cn.label), chain) += 1;
        UnaryTree(l,transform(cn,true))(t.span);
      case BinaryTree(l,lchild,rchild) =>
        if(parentWasUnary) BinaryTree(l,transform(lchild,false),transform(rchild,false))(t.span);
        else UnaryTree(l,BinaryTree(l,transform(lchild,false),transform(rchild,false))(t.span))(t.span);
      case NullaryTree(l) =>
        if(parentWasUnary) NullaryTree(l)(t.span);
        else UnaryTree(l,NullaryTree(l)(t.span))(t.span);
      case t => t;
    }


    for( (t,w) <- trees) {
      val tn = transform(t,true);
      buf += (tn -> w);
    }

    (buf,chainReplacer(counts, trans));
  }

  private def stripChain(t: BinarizedTree[L]):(List[L],BinarizedTree[L]) = t match {
    case UnaryTree(l,c) =>
      val (chain,tn) = stripChain(c);
      (trans(l) :: chain, tn);
    case _ => (List.empty,t);
  }
}

object UnaryChainRemover {
  private def chainReplacer[L](counts: Counter2[(L,L),Seq[L],Int], trans: L=>L) = {
    val maxes = counts.domain._1.map{ labels => (labels -> counts(labels,::).argmax)}.toMap;

    new ChainReplacer[L]  {
      def replacementFor(parent: L, child: L) = {
        if(parent == child) Seq.empty
        else maxes.getOrElse(trans(parent) -> trans(child), Seq.empty);
      }

      protected def transform(l: L) = trans(l)
    }

  }
  trait ChainReplacer[L] {
    def replacementFor(parent: L, child: L):Seq[L];
    protected def transform(l: L):L

    def replaceUnaries(t: Tree[L]):Tree[L] = t match {
      case UnaryTree(a,child) if transform(a) == transform(child.label) =>
        replaceUnaries(child)
      case UnaryTree(a,child) =>
        val c = child.label;
        val replacements = replacementFor(transform(a),transform(c));
        val withChain = replacements.foldRight(replaceUnaries(child).asInstanceOf[BinarizedTree[L]])( (lbl,child) => UnaryTree(lbl,child)(child.span));
        UnaryTree(a,withChain)(t.span);
      case t@BinaryTree(a,lchild,rchild) =>
        BinaryTree(a,replaceUnaries(lchild).asInstanceOf[BinarizedTree[L]],replaceUnaries(rchild).asInstanceOf[BinarizedTree[L]])(t.span)
      case t => t;
    }
  }
}