package collins.util.parsers

import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.XML

import collins.models.lldp.Chassis
import collins.models.lldp.ChassisId
import collins.models.lldp.Interface
import collins.models.lldp.Port
import collins.models.lldp.PortId
import collins.models.lldp.Vlan
import collins.util.LldpRepresentation
import collins.util.config.LldpConfig

class LldpParser(txt: String) extends CommonParser[LldpRepresentation](txt) {
  override def parse(): Either[Throwable, LldpRepresentation] = {
    val xml = try {
      XML.loadString(txt)
    } catch {
      case e: Throwable =>
        logger.info("Invalid XML specified: " + e.getMessage)
        return Left(e)
    }
    val rep = try {
      getInterfaces(xml).foldLeft(LldpRepresentation(Nil)) {
        case (holder, interface) =>
          holder.copy(interfaces = toInterface(interface) +: holder.interfaces)
      }
    } catch {
      case e: Throwable =>
        logger.warn("Caught exception processing LLDP XML: " + e.getMessage)
        return Left(e)
    }
    Right(rep.copy(interfaces = rep.interfaces.reverse))
  }

  protected def toInterface(seq: NodeSeq): Interface = {
    val name = (seq \ "@name" text)
    val chassis = findChassis(seq)
    val port = findPort(seq)
    val vlans = findVlans(seq)
    Interface(name, chassis, port, vlans)
  }

  protected def findChassis(seq: NodeSeq): Chassis = {
    val chassis = (seq \\ "chassis")
    val name = (chassis \\ "name" text)
    val id = (chassis \\ "id")
    val idType = (id \ "@type" text)
    val idValue = id.text
    val description = (chassis \\ "descr" text)
    requireNonEmpty(
      (idType -> "chassis id type"), (idValue -> "chassis id value"),
      (name -> "chassis name"), (description -> "chassis description"))
    Chassis(name, ChassisId(idType, idValue), description)
  }

  protected def findPort(seq: NodeSeq): Port = {
    val port = (seq \\ "port")
    val id = (port \\ "id")
    val idType = (id \ "@type" text)
    val idValue = id.text
    val description = (port \\ "descr" text)
    requireNonEmpty((idType -> "port id type"), (idValue -> "port id value"), (description -> "port description"))
    Port(PortId(idType, idValue), description)
  }

  protected def findVlans(seq: NodeSeq): Seq[Vlan] = {
    // TODO(gabe): make this less brittle and handle missing vlan-id
    (seq \\ "vlan").foldLeft(Seq[Vlan]()) {
      // some switches don't report a vlan-id, despite a VLAN being configured
      // on an interface. Lets be flexible here and allow it to be empty.
      case (vseq, vlan) =>
        val idOpt = Option(vlan \ "@vlan-id" text).filter(_.nonEmpty)
        val name = vlan.text
        if (LldpConfig.requireVlanName) {
          requireNonEmpty((name -> "vlan name"))
        }
        if (LldpConfig.requireVlanId) {
          requireNonEmpty((idOpt.getOrElse("") -> "vlan id"))
        }
        val id = idOpt.map(_.toInt).getOrElse(0)
        Vlan(id, name) +: vseq
    }
  }

  protected def getInterfaces(elem: Elem): NodeSeq = {
    val elems = (elem \\ "interface").filter { node =>
      (node \ "@label" text) == "Interface"
    }
    if (elems.size < 1) {
      throw new AttributeNotFoundException("Couldn't find interface nodes in XML")
    } else {
      elems
    }
  }

  private def requireNonEmpty(seq: (String, String)*) {
    seq.foreach { i =>
      if (i._1.isEmpty) {
        throw new AttributeNotFoundException("Found empty " + i._2)
      }
    }
  }

}
