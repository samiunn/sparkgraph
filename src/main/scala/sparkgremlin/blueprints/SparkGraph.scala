package sparkgremlin.blueprints

import com.tinkerpop.blueprints._
import com.tinkerpop.blueprints.util.wrappers.readonly.{ReadOnlyGraph,ReadOnlyVertex}
import com.tinkerpop.blueprints.Compare

import java.lang.Iterable

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}

import collection.JavaConverters._

import org.apache.spark.SparkContext._
import org.apache.spark.rdd._

import org.apache.spark.{graphx, SparkContext}
import scala.util.Random
import org.apache.spark.storage.StorageLevel
import sparkgremlin.blueprints.io._
import sparkgremlin.blueprints.io.build._

import org.apache.spark.graphx.impl.{GraphImpl => GraphXImplGraph, EdgePartitionBuilder}
import scala.reflect.ClassTag
import com.tinkerpop.blueprints.Edge

object SparkGraph {
  val FEATURES = new Features();
  FEATURES.supportsDuplicateEdges = true;
  FEATURES.supportsSelfLoops = true;
  FEATURES.isPersistent = false;
  FEATURES.supportsVertexIteration = true;
  FEATURES.supportsEdgeIteration = true;
  FEATURES.supportsVertexIndex = false;
  FEATURES.supportsEdgeIndex = false;
  FEATURES.ignoresSuppliedIds = true;
  FEATURES.supportsEdgeRetrieval = true;
  FEATURES.supportsVertexProperties = true;
  FEATURES.supportsEdgeProperties = true;
  FEATURES.supportsTransactions = false;
  FEATURES.supportsIndices = false;

  FEATURES.supportsSerializableObjectProperty = true;
  FEATURES.supportsBooleanProperty = true;
  FEATURES.supportsDoubleProperty = true;
  FEATURES.supportsFloatProperty = true;
  FEATURES.supportsIntegerProperty = true;
  FEATURES.supportsPrimitiveArrayProperty = true;
  FEATURES.supportsUniformListProperty = true;
  FEATURES.supportsMixedListProperty = true;
  FEATURES.supportsLongProperty = true;
  FEATURES.supportsMapProperty = true;
  FEATURES.supportsStringProperty = true;

  FEATURES.isWrapper = true;
  FEATURES.supportsKeyIndices = false;
  FEATURES.supportsVertexKeyIndex = false;
  FEATURES.supportsEdgeKeyIndex = false;
  FEATURES.supportsThreadedTransactions = false;

  val READ_ONLY_MESSAGE = "SparkGraph is ReadOnly";
  val WRITE_ONLY_MESSAGE = "InputGraph for Write Only"
  val NOT_READY_MESSAGE = "Element not connected to Graph"

  def generate(sc:SparkContext) : SparkGraph = {
    return new SparkGraph(sc.parallelize(Array[(Long,SparkVertex)]()), sc.parallelize(Array[SparkEdge]()));
  }

  def reduceVertex(v1:SparkVertex, v2:SparkVertex) : SparkVertex = {
    var out : SparkVertex = new SparkVertex(v1.getID, null)
    out.edgeSet = v1.edgeSet ++ v2.edgeSet
    for ( k <- v1.getPropertyKeys.asScala) {
      out.setProperty(k, v1.getProperty(k))
    }
    for ( k <- v2.getPropertyKeys.asScala) {
      out.setProperty(k, v2.getProperty(k))
    }
    return out
  }

  def mergeVertex(vertexId:Long, vset1:java.lang.Iterable[SparkVertex], vset2:java.lang.Iterable[SparkVertex]) : SparkVertex = {
    var out : SparkVertex = new SparkVertex(vertexId, null)
    var edges = new ArrayBuffer[SparkEdge]()
    for (a <- vset1.asScala) {
      if (a.edgeSet != null) edges ++= a.edgeSet
      for ( k <- a.getPropertyKeys.asScala) {
        out.setProperty(k, a.getProperty(k))
      }
    }
    for (a <- vset2.asScala) {
      if (a.edgeSet != null) edges ++= a.edgeSet
      for ( k <- a.getPropertyKeys.asScala) {
        out.setProperty(k, a.getProperty(k))
      }
    }
    out.edgeSet = edges.toArray
    return out;
  }

  def cachedVertices2Graph(vertices: RDD[SparkVertex]) : graphx.Graph[SparkVertex,SparkEdge] = {
    val edges = vertices.flatMap( x => x.edgeSet ).map( x => graphx.Edge( x.outVertexId, x.inVertexId, x ) )
    val fringeVerts = vertices.flatMap( x => x.edgeSet.map( y => {val z = y.getVertex(Direction.IN); (z.getId.asInstanceOf[Long], z.asInstanceOf[SparkVertex])  } ) )
    val rdd = vertices.map( x => (x.getId.asInstanceOf[Long], x) ).cogroup(fringeVerts).map( x => (x._1, mergeVertex(x._1, x._2._1.asJava, x._2._2.asJava ) ) )
    val gr = graphx.Graph(rdd, edges)
    return gr
  }

}

class SparkGraph(var graph : graphx.Graph[SparkVertex,SparkEdge], defaultStorage: StorageLevel) extends SparkGraphElementSet[SparkGraphElement] with Graph {

  def this(graph : graphx.Graph[SparkVertex,SparkEdge]) = {
    this(graph, StorageLevel.MEMORY_ONLY)
  }

  def this(vertices:RDD[(Long,SparkVertex)], edges:RDD[SparkEdge]) = {
    this(graphx.Graph(vertices, edges.map( x => new graphx.Edge(x.outVertexId, x.inVertexId, x) )), StorageLevel.MEMORY_ONLY)
  }

  var updates = new ArrayBuffer[BuildElement]();

  override def toString() = "sparkgraph[nodes=" + graph.vertices.count + "]"

  def getFeatures: Features = SparkGraph.FEATURES;

  def flushUpdates() : Boolean = {
    if (updates.length == 0) {
      return false;
    }
    val u = graph.vertices.sparkContext.parallelize(updates)

    val newVertex = u.filter( ! _.isEdge ).map( x => (x.getVertexId.asInstanceOf[Long], x) ).groupByKey().map( x => (x._1, SparkGraphBuilder.vertexBuild(x._1, x._2.toSeq)) );
    val newEdges =  u.filter(  _.isEdge ).map( x => (x.getEdgeId.asInstanceOf[Long], x)).groupByKey().map( x => (x._1, SparkGraphBuilder.edgeBuild(x._1, x._2.toSeq)));

    val nextVerts = graph.vertices.cogroup( newVertex ).map( x => (x._1, SparkGraphBuilder.mergeVertex( x._2._1.toSeq, x._2._2.toSeq ) ) ).filter(x => x._2 != null)
    val nextEdges = graph.edges.map( x => (x.attr.id, x.attr) ).cogroup(newEdges).map( x => SparkGraphBuilder.mergeEdge(x._2._1.toSeq, x._2._2.toSeq) ).filter( _ != null ).map( x => new graphx.Edge(x.outVertexId, x.inVertexId, x) )

    graph = graphx.Graph(nextVerts, nextEdges)

    updates.clear();
    return true;
  }

  /**
   *
   * @param id
   * @return
   */
  def getEdge(id: Any): Edge = {
    if (id == null) {
      throw new IllegalArgumentException("Null ID value");
    }
    flushUpdates();
    val set = graph.edges.filter( x => x.attr.id == id ).take(1)
    if (set.length == 0) {
      return null;
    }
    val out = set.head.attr
    out.graph = this;
    return out.asInstanceOf[Edge];
  }

  /**
   *
   * @param id
   * @return
   */
  def getVertex(id: Any): Vertex = {
    if (id == null) {
      throw new IllegalArgumentException();
    }
    flushUpdates();
    try {
      val lid : java.lang.Long = id match {
        case x : java.lang.Long => id.asInstanceOf[java.lang.Long]
        case _ => id.toString.toLong
      }
      val set = graph.vertices.lookup(lid);
      if (set.length > 0) {
        val out = set.head;
        out.graph = this;
        return out;
      }
    } catch {
      case _ : java.lang.NumberFormatException => return null;
    }

    return null;
  }

  def shutdown() = graph = null

  /**
   *
   * @param edge
   */
  def removeEdge(edge: Edge) {
    updates += new EdgeRemoveBuild(edge.getId.asInstanceOf[Long]);
  }

  /**
   *
   * @param vertex
   */
  def removeVertex(vertex: Vertex) {
    val vertID = vertex.getId.asInstanceOf[Long].toLong
    for ( y <- graph.edges.filter( x => x.attr.inVertexId == vertID || x.attr.outVertexId == vertID ).map( _.attr.id ).collect()) {
      updates += new EdgeRemoveBuild(y)
    }
    updates += new VertexRemoveBuild(vertex.getId.asInstanceOf[Long]);
  }

  /**
   *
   * @param id
   * @return The newly created vertex
   */
  def addVertex(id: scala.AnyRef): Vertex =  {
    val u : java.lang.Long = id match {
      case x : java.lang.Long => x;
      case null => new java.lang.Long(Random.nextLong()); //(new java.lang.Long(Random.nextLong())).toString;
      case _ => {
        try {
          id.toString.toLong
        } catch {
          case _ : java.lang.NumberFormatException => new java.lang.Long(Random.nextLong())
        }
      }
    }
    updates += new VertexBuild(u);
    return new SparkVertex(u, this);
  }

  def getVertices: java.lang.Iterable[Vertex] = {
    flushUpdates();
    return new SimpleGraphElementSet[Vertex](this, graph.vertices.map(_._2), classOf[SparkVertex] )
    /*
    val flatRDD = graph.mapReduceTriplets[(SparkVertex,Array[SparkEdge])](
      x => {
        Iterator( (x.srcId, (x.srcAttr, Array(x.attr)  ) ), (x.dstId, (x.dstAttr, Array(x.attr))) )
      },
      (y,z) => {
        (y._1, y._2 ++ z._2)
      }
    )
    flatRDD
    assert(graph.vertices.count() == flatRDD.count())

    return new SparkGraphElementSet[Vertex] {
      def remove() = {}

      override def process(in: Any): Vertex = {
        val tin = in.asInstanceOf[(SparkVertex, Array[SparkEdge])]
        tin._1.edgeSet = tin._2.to[ArrayBuffer]
        tin._1.graph = SparkGraph.this
        tin._1
      }

      override def elementClass(): Class[_] = classOf[SparkVertex]
      override def elementRDD(): RDD[Vertex] = graph.vertices.mapValues( _.asInstanceOf[Vertex] ).values
      override def getRDD(): RDD[(SparkVertex, Array[SparkEdge])] = flatRDD.values
      override def graphX(): graphx.Graph[SparkVertex, SparkEdge] = graph
    }
    */
  }

  def getVertices(key: String, value: scala.Any): java.lang.Iterable[Vertex] = {
    flushUpdates();
    return new SimpleGraphElementSet[SparkVertex](this, graph.vertices.filter(x => x._2.getProperty(key) == value).map(_._2), classOf[SparkVertex] ).asInstanceOf[java.lang.Iterable[Vertex]]
  }

  def addEdge(id: Any, outVertex: Vertex, inVertex: Vertex, label: String): Edge = {
    if (label == null) {
      throw new IllegalArgumentException("Null Label");
    }
    val u : Any = id match {
      case null => new java.lang.Long(Random.nextLong());
      case _ => {
        try {
          id.toString.toLong
        } catch {
          case _ : java.lang.NumberFormatException => new java.lang.Long(Random.nextLong())
        }
      }
    }
    updates += new EdgeBuild(u.asInstanceOf[Long], outVertex.getId.asInstanceOf[Long], inVertex.getId.asInstanceOf[Long], label)
    return new SparkEdge(u.asInstanceOf[Long], outVertex.getId.asInstanceOf[Long], inVertex.getId.asInstanceOf[Long], label, this);
  }

  def getEdges: Iterable[Edge] = {
    flushUpdates();
    val out = graph.edges.map( x => x.attr );
    return new SimpleGraphElementSet[SparkEdge](this, out, classOf[SparkEdge]).asInstanceOf[Iterable[Edge]];
  }

  def getEdges(key: String, value: scala.Any): Iterable[Edge] = {
    flushUpdates();
    val out = graph.edges.filter( _.attr.labelMatch(key, value.toString) );
    return new SimpleGraphElementSet[SparkEdge](this, out.map(_.attr), classOf[SparkEdge]).asInstanceOf[java.lang.Iterable[Edge]];
  }

  def query(): GraphQuery = {
    return new SparkGraphQuery(this);
  }


  def elementClass() : Class[_] = classOf[SparkVertex];

  var graphCollect : Array[SparkGraphElement] = null;
  var graphCollectIndex = 0;

  def remove() = {};

  def graphX(): graphx.Graph[SparkVertex,SparkEdge] = {
    flushUpdates()
    graph
  };

  def elementRDD(): RDD[SparkGraphElement] = {
    //return curgraph.flatMap( x => x._2.edgeSet.map( _.asInstanceOf[SparkGraphElement] ) ).union( curgraph.map( _.asInstanceOf[SparkGraphElement]) );
    return graph.vertices.map( _._2 )
  }

  /*
  def getCachedVertices(dir : Direction) : RDD[SparkVertex] = {
    val flatRDD = graph.mapReduceTriplets[SparkVertex](  x => {
      val o_src = new SparkVertex(x.srcAttr.id, null)
      val o_dst = new SparkVertex(x.dstAttr.id, null)
      x.srcAttr.propMap.foreach( x => o_src.propMap(x._1) = x._2 )
      x.dstAttr.propMap.foreach( x => o_dst.propMap(x._1) = x._2 )
      if (dir == Direction.OUT) {
        o_src.edgeSet += x.attr
      } else if (dir == Direction.IN) {
        o_dst.edgeSet += x.attr
      } else {
        o_src.edgeSet += x.attr
        o_dst.edgeSet += x.attr
      }
      Iterator((x.srcId, o_src),(x.dstId,o_dst))
    },
      (y,z) => {
        y.edgeSet ++= z.edgeSet;
        y
    })
    flatRDD.map( _._2 )
  }
  */

  override def process(in: Any): SparkGraphElement = in.asInstanceOf[SparkGraphElement]

  override def getRDD(): RDD[SparkGraphElement] = graph.vertices.values.map( _.asInstanceOf[SparkGraphElement])
}
