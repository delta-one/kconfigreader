package de.fosd.typechef.kconfig

import scala.Some
import de.fosd.typechef.featureexpr.{FeatureExprFactory, FeatureExpr}
import FeatureExprFactory._

private object KConfigModel {

    val MODULES = createDefinedExternal("MODULES")

}

class KConfigModel() {

    import KConfigModel.MODULES

    val items: collection.mutable.Map[String, Item] = collection.mutable.Map()
    val choices: collection.mutable.Map[String, Choice] = collection.mutable.Map()

    def setType(itemName: String, _type: String) {
        getItem(itemName).setType(_type)
    }

    def getItem(itemName: String): Item =
        items.getOrElseUpdate(itemName, Item(itemName, this))

    def getChoice(choiceName: String): Choice =
        choices.getOrElseUpdate(choiceName, Choice(choiceName))

    override def toString() = items.toString() + "\n" + choices.toString()

    def getConstraints: List[FeatureExpr] =
        (items.values.flatMap(_.getConstraints) ++
            choices.values.flatMap(_.getConstraints)).toList ++
            (if (!items.contains(MODULES.feature)) List(MODULES.not) else Nil) //if no MODULES item, exclude it explicitly

    def getFM: FeatureExpr = {
        //        var f: FeatureExpr = True
        //        var d: String = ""
        //        for (c <- getConstraints) {
        //            assert((f and c).isSatisfiable(), "unsatisfiable because " + c + ", before \n" + d)
        //            d = d + "\n"+c
        //            f = f and c
        //        }

        val fm = getConstraints.foldLeft(True)(_ and _)
        assert(fm.isSatisfiable, "model is not satisfiable")
        fm
    }

    def getItems = items.keys.toSet

    def getBooleanSymbols: Set[String] = {
        val i = items.values.filterNot(_.name startsWith "CHOICE_")
        val boolitems = i.filter(_._type == "boolean")
        val triitems = i.filter(_._type == "tristate")
        (boolitems.map(_.name) ++ triitems.map(_.name) ++ triitems.map(_.name + "_MODULE")).toSet
    }

    def getNonBooleanDefaults: Map[Item, List[(String, Expr)]] =
        items.values.filter(Set("integer", "hex", "string") contains _._type).map(i => (i -> i.default)).toMap
}

/**
 * tristate to CONFIG_x translation:
 *
 * x=y
 * => #define CONFIG_x
 * => #undef CONFIG_x_MODULE
 *
 * x=m
 * => #undef CONFIG_x
 * => #define CONFIG_x_MODULE
 *
 * x=n
 * => #undef CONFIG_x
 * => #undef CONFIG_x_MODULE
 *
 * @param name
 */

case class Item(val name: String, model: KConfigModel) {


    var _type: String = "boolean"
    var hasPrompt: Expr = Not(YTrue())
    private[kconfig] var _default: List[(String, Expr)] = Nil
    var depends: Option[Expr] = None
    var selectedBy: List[(Item, Expr)] = Nil
    var isDefined: Boolean = false
    // an item may be created because it's referenced - when it's never used it is stored as undefined
    lazy val fexpr_y = FeatureExprFactory.createDefinedExternal(name)
    lazy val fexpr_both = if (isTristate) (fexpr_y or fexpr_m) else fexpr_y
    var tristateChoice = false //special hack for choices

    def setType(_type: String) = {
        this._type = _type
        this
    }

    def setDefined() = {
        isDefined = true
        this
    }

    import KConfigModel.MODULES

    def setPrompt(p: Expr) {
        this.hasPrompt = p
    }

    def setDefault(defaultValue: String, condition: Expr) {
        this._default = (defaultValue, condition) :: this._default
    }

    def setDepends(s: Expr) {
        this.depends = Some(s)
    }

    def setSelectedBy(item: Item, condition: Expr = YTrue()) {
        this.selectedBy = (item, condition) :: this.selectedBy
    }

    def getConstraints: List[FeatureExpr] = if (Set("boolean", "tristate") contains _type) {
        var result: List[FeatureExpr] = Nil

        //dependencies
        if (depends.isDefined) {
            if (isTristate) {
                result ::= this.fexpr_y implies depends.get.fexpr_y
                result ::= this.fexpr_m implies depends.get.fexpr_both
            } else
                result ::= this.fexpr_y implies depends.get.fexpr_both
        }

        //invisible options
        var promptCondition = hasPrompt
        if (promptCondition == this.depends.getOrElse(YTrue())) promptCondition == YTrue()
        if (promptCondition != YTrue()) {
            val nopromptCond = promptCondition.fexpr_both.not() //the if simplifies the formula in a common case. should be equivalent overall
            val defaults = getDefaults()
            val default_y = getDefault_y(defaults)
            val default_m = getDefault_m(defaults)
            val default_both = default_y or default_m
            //            println("y=" + default_y + ";m=" + default_m + ";both=" + default_both)

            //if invisible and off by default, then can only be activated by selects
            // notDefault -> !this | dep1 | dep2 | ... | depn
            if (isTristate) {
                result ::= nopromptCond implies (MODULES implies (default_y.not implies selectedBy.foldLeft(this.fexpr_y.not)((expr, sel) => (sel._1.fexpr_y and sel._2.fexpr_y) or expr)))
                result ::= nopromptCond implies (MODULES implies (default_m.not implies selectedBy.foldLeft(this.fexpr_m.not)((expr, sel) => (sel._1.fexpr_m and sel._2.fexpr_both) or expr)))
                result ::= nopromptCond implies (MODULES.not implies (default_both.not implies selectedBy.foldLeft(this.fexpr_y.not)((expr, sel) => (sel._1.fexpr_both and sel._2.fexpr_both) or expr)))
            } else
                result ::= nopromptCond implies ((default_both.not implies selectedBy.foldLeft(this.fexpr_y.not)((expr, sel) => (sel._1.fexpr_both and sel._2.fexpr_both) or expr)))

            //if invisible and on by default, then can only be deactivated by dependencies (== default conditions)
            // default -> this <=> defaultCondition
            if (isTristate) {
                result ::= nopromptCond implies (default_y implies this.fexpr_y)
                result ::= nopromptCond implies (default_m implies this.fexpr_both)
            } else {
                var c = (default_both implies this.fexpr_y)
                //special hack for tristate choices, that are optional if modules are selected but mandatory otherwise
                if (tristateChoice) c = MODULES.not implies c
                result ::= nopromptCond implies c
            }
        }

        if (isTristate) {
            result ::= (this.fexpr_y and this.fexpr_m).not
            result ::= (this.fexpr_m implies MODULES)
        }


        //selected by any select-dependencies
        // -> (dep1 | dep2 | ... | depn) -> this
        for (sel <- selectedBy) {
            result ::= ((sel._1.fexpr_y and sel._2.fexpr_y) implies this.fexpr_y)
            result ::= ((sel._1.fexpr_both and sel._2.fexpr_both) implies this.fexpr_both)
        }

        result
    } else Nil

    /**
     * returns the different defaults and their respective conditions
     *
     * this evaluates conditions to booleans(!), i.e., m and y are both considered true
     */
    def getDefaults(): Map[String, FeatureExpr] = {
        var result: Map[String, FeatureExpr] = Map()
        var covered: FeatureExpr = False

        def updateResult(v: String, newCond: FeatureExpr) {
            val prevCondition = result.getOrElse(v, False)
            val cond = prevCondition or (newCond andNot covered)
            result += (v -> cond)
            covered = covered or newCond
        }

        for ((v, expr) <- _default.reverse) {
            if (v == "y" && isTristate) {
                updateResult(v, expr.fexpr_y)
                updateResult("m", expr.fexpr_m)
            } else
                updateResult(v, expr.fexpr_both)

        }
        result
    }

    def default: List[(String, Expr)] = {
        var result = _default
        //        if (_type == "integer" && hasPrompt)
        //            result = ("0", ETrue()) :: result
        //        if (_type == "hex" && hasPrompt)
        //            result = ("0x0", ETrue()) :: result
        //        if (_type == "string" && hasPrompt)
        //            result = ("", ETrue()) :: result
        if (_type == "string")
            result = result.map(v => ("\"" + v._1 + "\"", v._2))
        result
    }

    def getDefault_y(defaults: Map[String, FeatureExpr]) =
        defaults.filterKeys(model.items.contains(_)).map(
            e => model.getItem(e._1).fexpr_y and e._2
        ).foldLeft(
                defaults.getOrElse("y", False))(
                _ or _
            )

    def getDefault_m(defaults: Map[String, FeatureExpr]) =
        defaults.filterKeys(model.items.contains(_)).map(
            e => model.getItem(e._1).fexpr_m and e._2
        ).foldLeft(
                defaults.getOrElse("m", False))(
                _ or _
            )


    def isTristate = _type == "tristate"

    lazy val modulename = if (isTristate) this.name + "_MODULE" else name
    lazy val fexpr_m = if (isTristate) FeatureExprFactory.createDefinedExternal(modulename) else False

    override def toString = "Item " + name
}


case class Choice(val name: String) {
    var required: String = ""
    var _type: String = "boolean"
    var items: List[Item] = Nil
    lazy val fexpr_y = FeatureExprFactory.createDefinedExternal(name)
    lazy val fexpr_m = if (isTristate) FeatureExprFactory.createDefinedExternal(name + "_MODULE") else False
    lazy val fexpr_both = fexpr_m or fexpr_y

    import KConfigModel.MODULES

    def setType(_type: String) = {
        this._type = _type
        this
    }

    def setRequired(p: String) = {
        this.required = p
        this
    }

    def addItem(p: Item) = {
        this.items = p :: this.items
        this
    }

    def getConstraints: List[FeatureExpr] = {
        var result: List[FeatureExpr] = List()
        //whether choices are mandatory or depend on others are set by the Items abstraction, not here
        //choice -> at least one child
        result ::= (this.fexpr_both implies (items.foldLeft(False)(_ or _.fexpr_both)))
        //every option implies the choice
        result ++= items.map(_.fexpr_both implies this.fexpr_both)
        //children can only select "m" if entire choice is "m"
        if (isTristate)
            result ++= items.filter(_.isTristate).map(_.fexpr_m implies this.fexpr_m)
        //all options are mutually exclusive in "y" setting (not in "m")
        result ++=
            (for (a <- items.tails.take(items.size); b <- a.tail) yield (a.head.fexpr_y mex b.fexpr_y))
        //if one entry is selected as "y" no other entry may be selected as "m"
        if (isTristate)
            result ++= (for (a <- items) yield
                a.fexpr_y implies items.foldLeft(True)((f, i) => f and i.fexpr_m.not()))

        if (isTristate) {
            result ::= (fexpr_m mex fexpr_y)
            result ::= (fexpr_m implies MODULES)
        }

        //this is mandatory
        result
    }

    def isTristate = _type == "tristate"
}
