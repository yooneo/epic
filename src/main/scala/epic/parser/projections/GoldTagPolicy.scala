package epic.parser.projections

/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import epic.trees.BinarizedTree
import breeze.collection.mutable.TriangularArray

trait GoldTagPolicy[L] {
  def isGoldTag(start: Int, end: Int, tag: Int): Boolean
}

object GoldTagPolicy {
  def noGoldTags[L]:GoldTagPolicy[L] = new GoldTagPolicy[L] {
    def isGoldTag(start: Int, end: Int, tag: Int) = false
  }

  def candidateTreeForcing[L](tree: BinarizedTree[Seq[Int]]):GoldTagPolicy[L] ={
    val gold = TriangularArray.raw(tree.span.end+1,collection.mutable.BitSet());
    if(tree != null) {
      for( t <- tree.allChildren) {
        gold(TriangularArray.index(t.span.start,t.span.end)) ++= t.label
      }
    }
    new GoldTagPolicy[L] {
      def isGoldTag(start: Int, end: Int, tag: Int) = {
        val set = gold(TriangularArray.index(start,end))
        set != null && set.contains(tag)
      }
    }
  }


  def goldTreeForcing[L](trees: BinarizedTree[Int]*):GoldTagPolicy[L] ={
    val gold = TriangularArray.raw(trees.head.span.end+1,collection.mutable.BitSet());
    for(tree <- trees) {
      if(tree != null) {
        for( t <- tree.allChildren) {
          gold(TriangularArray.index(t.span.start,t.span.end)) += t.label
        }
      }
    }
    new GoldTagPolicy[L] {
      def isGoldTag(start: Int, end: Int, tag: Int) = {
        gold(TriangularArray.index(start,end)) contains tag
      }
    }
  }
}