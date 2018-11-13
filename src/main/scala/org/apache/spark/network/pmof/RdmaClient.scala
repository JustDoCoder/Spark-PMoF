package org.apache.spark.network.pmof

import java.nio.ByteBuffer
import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingDeque}

import com.intel.hpnl.core._

class RdmaClient(address: String, port: Int) {
  val eqService = new EqService(address, port.toString, false)
  val cqService = new CqService(eqService, 1, eqService.getNativeHandle)
  val connectHandler = new ClientConnectHandler(this)
  val recvHandler = new ClientRecvHandler(this)
  val readHandler = new ClientReadHandler(this)

  val outstandingReceiveFetches: ConcurrentHashMap[Int, ReceivedCallback] = new ConcurrentHashMap[Int, ReceivedCallback]()
  val outstandingReadFetches: ConcurrentHashMap[Int, (Int, ReadCallback)] = new ConcurrentHashMap[Int, (Int, ReadCallback)]()
  val shuffleBufferMap: ConcurrentHashMap[Int, ShuffleBuffer] = new ConcurrentHashMap[Int, ShuffleBuffer]()

  final val SINGLE_BUFFER_SIZE: Int = RdmaTransferService.CHUNKSIZE
  final val BUFFER_NUM: Int = 16
  private var con: Connection = _

  private val deferredReqList = new LinkedBlockingDeque[ClientDeferredReq]()
  private val deferredReadList = new LinkedBlockingDeque[ClientDeferredRead]()

  def init(): Unit = {
    for (i <- 0 until BUFFER_NUM) {
      val sendBuffer = ByteBuffer.allocateDirect(SINGLE_BUFFER_SIZE)
      eqService.setSendBuffer(sendBuffer, SINGLE_BUFFER_SIZE, i)
    }
    for (i <- 0 until BUFFER_NUM * 2) {
      val recvBuffer = ByteBuffer.allocateDirect(SINGLE_BUFFER_SIZE)
      eqService.setRecvBuffer(recvBuffer, SINGLE_BUFFER_SIZE, i)
    }
    cqService.addExternalEvent(new ExternalHandler {
      override def handle(): Unit = {
        handleDeferredReq()
        handleDeferredRead()
      }
    })
  }

  def start(): Unit = {
    eqService.setConnectedCallback(connectHandler)
    eqService.setRecvCallback(recvHandler)
    eqService.setReadCallback(readHandler)

    cqService.start()
    eqService.start(1)
    eqService.waitToConnected()
  }

  def stop(): Unit = {
    cqService.shutdown()
  }

  def waitToStop(): Unit = {
    cqService.join()
    eqService.shutdown()
    eqService.join()
  }

  def setCon(con: Connection): Unit = {
    this.con = con
  }

  def getCon: Connection = {
    assert(this.con != null)
    this.con
  }

  def handleDeferredReq(): Unit = {
    if (!deferredReqList.isEmpty) {
      val deferredReq = deferredReqList.pollFirst()
      val byteBuffer = deferredReq.byteBuffer
      val seq = deferredReq.seq
      val blockIndex = deferredReq.blockIndex
      val callback = deferredReq.callback
      send(byteBuffer, seq, blockIndex, callback, isDeferred = true)
    }
  }

  def handleDeferredRead(): Unit = {
    if (!deferredReadList.isEmpty) {
      val deferredRead = deferredReadList.pollFirst()
      read(deferredRead.shuffleBuffer, deferredRead.blockIndex, deferredRead.seq, deferredRead.reqSize, deferredRead.rmaAddress, deferredRead.rmaRkey, null, isDeferred = true)
    }
  }

  def read(shuffleBuffer: ShuffleBuffer, blockIndex: Int,
           seq: Int, reqSize: Int, rmaAddress: Long, rmaRkey: Long,
           callback: ReadCallback, isDeferred: Boolean = false): Unit = {
    if (!isDeferred) {
      outstandingReadFetches.putIfAbsent(shuffleBuffer.getRdmaBufferId, (blockIndex, callback))
      shuffleBufferMap.putIfAbsent(shuffleBuffer.getRdmaBufferId, shuffleBuffer)
    }
    val ret = con.read(shuffleBuffer.getRdmaBufferId, 0, reqSize, rmaAddress, rmaRkey)
    if (ret == -11) {
      if (isDeferred)
        deferredReadList.addFirst(new ClientDeferredRead(shuffleBuffer, blockIndex, seq, reqSize, rmaAddress, rmaRkey))
      else
        deferredReadList.addLast(new ClientDeferredRead(shuffleBuffer, blockIndex, seq, reqSize, rmaAddress, rmaRkey))
    }
  }

  def send(byteBuffer: ByteBuffer, seq: Int, blockIndex: Int,
           callback: ReceivedCallback, isDeferred: Boolean): Unit = {
    assert(con != null)
    outstandingReceiveFetches.putIfAbsent(seq, callback)
    val sendBuffer = this.con.getSendBuffer(false)
    if (sendBuffer == null) {
      if (isDeferred) {
        deferredReqList.addFirst(new ClientDeferredReq(byteBuffer, seq, blockIndex, callback))
      } else {
        deferredReqList.addLast(new ClientDeferredReq(byteBuffer, seq, blockIndex, callback))
      }
      return
    }
    sendBuffer.put(byteBuffer, 0, 0, seq)
    con.send(sendBuffer.remaining(), sendBuffer.getRdmaBufferId)
  }

  def getEqService(): EqService = {
    eqService
  }

  def getOutStandingReceiveFetches(seq: Int): ReceivedCallback = {
    outstandingReceiveFetches.get(seq)
  }

  def getOutStandingReadFetches(seq: Int): (Int, ReadCallback) = {
    outstandingReadFetches.get(seq)
  }

  def getShuffleBuffer(rdmaBufferId: Int): ShuffleBuffer = {
    shuffleBufferMap.get(rdmaBufferId)
  }
}

class ClientConnectHandler(client: RdmaClient) extends Handler {
  override def handle(connection: Connection, rdmaBufferId: Int, bufferBufferSize: Int): Unit = {
    client.setCon(connection)
  }
}

class ClientRecvHandler(client: RdmaClient) extends Handler {
  override def handle(con: Connection, rdmaBufferId: Int, blockBufferSize: Int): Unit = {
    val buffer: RdmaBuffer = con.getRecvBuffer(rdmaBufferId)
    val rpcMessage: ByteBuffer = buffer.get(blockBufferSize)
    val seq = buffer.getSeq
    val callback = client.getOutStandingReceiveFetches(seq)
    callback.onSuccess(0, rpcMessage)
  }
}

class ClientReadHandler(client: RdmaClient) extends Handler {
  override def handle(con: Connection, rdmaBufferId: Int, blockBufferSize: Int): Unit = {
    val blockIndex = client.getOutStandingReadFetches(rdmaBufferId)._1
    val callback = client.getOutStandingReadFetches(rdmaBufferId)._2
    val shuffleBuffer = client.getShuffleBuffer(rdmaBufferId)
    callback.onSuccess(blockIndex, shuffleBuffer)
  }
}

class ClientDeferredReq(var byteBuffer: ByteBuffer, var seq: Int, var blockIndex: Int,
                        var callback: ReceivedCallback) {}

class ClientDeferredRead(val shuffleBuffer: ShuffleBuffer, val blockIndex: Int, val seq: Int, val reqSize: Int, val rmaAddress: Long, val rmaRkey: Long) {}
