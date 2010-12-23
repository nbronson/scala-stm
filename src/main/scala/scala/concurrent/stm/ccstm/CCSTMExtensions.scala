package scala.concurrent.stm
package ccstm

object CCSTMExtensions {
  def embalm(identity: Int, ref: Ref[_]) {
    CCSTM.embalm(identity, ref.asInstanceOf[Handle.Provider[_]].handle)
  }

  def resurrect(identity: Int, ref: Ref[_]) {
    CCSTM.resurrect(identity, ref.asInstanceOf[Handle.Provider[_]].handle)
  }
}