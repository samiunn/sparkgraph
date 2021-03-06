package sparkgraph.blueprints

import collection.JavaConverters._

import com.tinkerpop.blueprints.{Direction, Edge, Vertex}
import sparkgraph.blueprints.io.build.{EdgeRemoveBuild, EdgePropertyBuild}
import scala.collection.mutable.HashMap

/**
 *
 * @param id
 * @param outVertexId
 * @param inVertexId
 * @param label
 * @param outVertexCache
 * @param inVertexCache
 */
class SparkEdge(
                 val id:Long,
                 val outVertexId: Long,
                 val inVertexId: Long,
                 val label:String,
                 @transient var graph:SparkGraph,
                 @transient var outVertexCache : Vertex = null,
                 @transient var inVertexCache : Vertex = null
                 ) extends SparkGraphElement with Edge with Serializable {

  def getId: AnyRef = id.asInstanceOf[AnyRef];
  def getID: Long = id

  val propMap = new HashMap[String,Any]();

  override def equals(other: Any) = other match {
    case that: SparkEdge => (this.id == that.id)
    case that: Edge => (this.id == that.getId)
    case _ => false
  }

  def setGraph(inGraph: SparkGraph) = {
    graph = inGraph
  }

  def getGraph() : SparkGraph = graph

  override def hashCode() = id.hashCode

  override def setProperty(key:String, value:AnyRef) = {
    if (key == "id" || key == "label") {
      throw new IllegalArgumentException("Invalid Key String");
    }
    if (key == null || key.length == 0) {
      throw new IllegalArgumentException("Invalid Key String");
    }
    if (value == null) {
      throw new IllegalArgumentException("Invalid Property Value");
    }
    propMap(key) = value;
    if (graph != null) {
      graph.updates += new EdgePropertyBuild(id, outVertexId, inVertexId, key, value);
    }
  }

  def remove() = {
    if (graph == null) {
      throw new UnsupportedOperationException(SparkGraph.NOT_READY_MESSAGE);
    }
    graph.updates += new EdgeRemoveBuild(id);
  }

  /**
   *
   * @param direction
   * @return
   */
  def getVertex(direction: Direction): Vertex = {
    if (graph == null) {
      if (direction == Direction.IN) {
        if (inVertexCache == null) {
          return new SparkVertex(inVertexId, null);
        } else {
          return inVertexCache
        }
      } else if (direction == Direction.OUT) {
        if (outVertexCache == null) {
          return new SparkVertex(outVertexId, null);
        } else {
          return outVertexCache
        }
      }
      throw new IllegalArgumentException("Bad Edge Direction")
    } else {
      if (direction == Direction.IN) {
        return graph.getVertex(inVertexId);
      } else if (direction == Direction.OUT) {
        return graph.getVertex(outVertexId);
      }
      throw new IllegalArgumentException("Bad Edge Direction")
    }
  }

  def labelMatch(args:String*) : Boolean = {
    if (args.length == 1)
      return label == args(0)
    if (args.length == 0) {
      return true;
    }
    if (args.length == 2) {
      return propMap.getOrElse(args(0), null) == args(1);
    }
    return false;
  }

  def getLabel: String = label;

  def getProperty[T](key: String): T = {
    propMap.get(key).getOrElse(null).asInstanceOf[T];
  }

  def getPropertyKeys: java.util.Set[String] = propMap.keySet.asJava;

  def removeProperty[T](key: String): T = {
    return propMap.remove(key).orNull.asInstanceOf[T];
  }


  override def toString() : String = {
    return "%s %s (%s,%s) [%s]".format( id,label, outVertexId, inVertexId, propMap.map( x => "%s:%s".format(x._1, x._2) ).mkString(",") )
  }

}
