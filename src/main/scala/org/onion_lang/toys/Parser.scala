package org.onion_lang
package toys
import scala.util.parsing.combinator.RegexParsers
import util.parsing.input.{Reader, CharSequenceReader}

/**
 * @author Kota Mizushima
 */
class Parser extends RegexParsers {
  override def skipWhitespace = false
  private def not[T](p: => Parser[T], msg: String): Parser[Unit] = {
    not(p) | failure(msg)
  }
  lazy val EOF: Parser[String] = not(elem(".", (ch: Char) => ch != CharSequenceReader.EofCh), "EOF Expected") ^^ {_.toString}
  lazy val SPACING: Parser[String] = """\s*""".r
  lazy val SPACING_WITHOUT_LF: Parser[String] = ("\t" | " " | "\b" | "\f").* ^^ {_.mkString}
  lazy val LINEFEED: Parser[String] = ("\r\n" | "\r" | "\n")
  lazy val SEMICOLON: Parser[String] = ";"
  lazy val TERMINATOR: Parser[String] = (LINEFEED | SEMICOLON | EOF) <~ SPACING

  def CL[T](parser: Parser[T]): Parser[T] = parser <~ SPACING
  def token(parser: Parser[String]): Parser[String] = parser <~ SPACING_WITHOUT_LF
  lazy val LT: Parser[String]        = token("<")
  lazy val GT: Parser[String]        = token("<")
  lazy val PLUS: Parser[String]      = token("+")
  lazy val MINUS: Parser[String]     = token("-")
  lazy val ASTER: Parser[String]     = token("*")
  lazy val SLASH: Parser[String]     = token("/")
  lazy val LPAREN: Parser[String]    = token("(")
  lazy val RPAREN: Parser[String]    = token(")")
  lazy val LBRACE: Parser[String]    = token("{")
  lazy val RBRACE: Parser[String]    = token("}")
  lazy val IF: Parser[String]        = token("if")
  lazy val ELSE: Parser[String]      = token("else")
  lazy val COMMA: Parser[String]     = token(",")
  lazy val DOT: Parser[String]       = token(".")
  lazy val PRINTLN: Parser[String]   = token("println")
  lazy val CLASS: Parser[String]     = token("class")
  lazy val DEF: Parser[String]       = token("def")
  lazy val VAL: Parser[String]       = token("val")
  lazy val EQ: Parser[String]        = token("=")
  lazy val ARROW: Parser[String]     = token("=>")
  lazy val NEW: Parser[String]       = token("new")

  //lines ::= line {TERMINATOR expr} [TERMINATOR]
  def lines: Parser[AstNode] = repsep(line, TERMINATOR)<~opt(TERMINATOR)^^Block

  //line ::= expression | val_declaration | functionDefinition
  def line: Parser[AstNode] = expression | val_declaration | functionDefinition

  //expression ::= conditional | if | printLine
  def expression: Parser[AstNode] = assignment|conditional|ifExpr|printLine

  //if ::= "if" "(" expression ")" expression "else" expression
  def ifExpr: Parser[AstNode] = CL(IF) ~ CL(LPAREN) ~> expression ~ CL(RPAREN) ~ expression ~ CL(ELSE) ~ expression^^{
    case cond~_~pos~_~neg => IfExpr(cond, pos, neg)
  }

  //conditional ::= add {"<" add}
  def conditional: Parser[AstNode] = chainl1(add,
    CL(LT) ^^{op => (left:AstNode, right:AstNode) => LessOp(left, right)})

  //add ::= term {"+" term | "-" term}
  def add: Parser[AstNode] = chainl1(term,
    CL(PLUS) ^^ {op => (left:AstNode, right:AstNode) => AddOp(left, right)}|
    CL(MINUS) ^^ {op => (left:AstNode, right:AstNode) => SubOp(left, right)})

  //term ::= factor {"*" factor | "/" factor}
  def term : Parser[AstNode] = chainl1(invocation,
    CL(ASTER) ^^ {op => (left:AstNode, right:AstNode) => MulOp(left, right)}|
    CL(SLASH) ^^ {op => (left:AstNode, right:AstNode) => DivOp(left, right)})

  def invocation: Parser[AstNode] = application ~ ((CL(DOT) ~> ident) ~ opt(CL(LPAREN) ~> repsep(expression, CL(COMMA)) <~ RPAREN)).* ^^ {
    case self ~ Nil =>
      self
    case self ~ npList  =>
      npList.foldLeft(self){case (self, name ~ params) => MethodCall(self, name, params.getOrElse(Nil))}
  }

  def application: Parser[AstNode] = primary ~ opt(CL(LPAREN) ~> repsep(expression, CL(COMMA)) <~ RPAREN)^^ {
    case fac ~ param => {
      param match {
        case Some(p) => FunctionCall(fac, p)
        case None => fac
      }
    }
  }

  //primary ::= intLiteral | stringLiteral | "(" expression ")" | "{" lines "}"
  def primary: Parser[AstNode] = intLiteral | stringLiteral | newObject | ident | anonFun | CL(LPAREN) ~>expression<~ RPAREN | CL(LBRACE) ~>lines<~ RBRACE | hereDocument | hereExpression

  //intLiteral ::= ["1"-"9"] {"0"-"9"}
  def intLiteral : Parser[AstNode] = ("""[1-9][0-9]*|0""".r^^{ value => IntNode(value.toInt)}) <~ SPACING_WITHOUT_LF

  //stringLiteral ::= "\"" ((?!")(\[rnfb"'\\]|[^\\]))* "\""
  def stringLiteral : Parser[AstNode] = ("\""~> ("""((?!("|#\{))(\[rnfb"'\\]|[^\\]))+""".r ^^ StringNode | "#{" ~> expression <~ "}").*  <~ "\"" ^^ { values =>
    values.foldLeft(StringNode(""):AstNode) { (ast, content) => AddOp(ast, content) }
  }) <~ SPACING_WITHOUT_LF

  def fqcn: Parser[String] = (ident ~ (CL(DOT) ~ ident).*) ^^ { case id ~ ids => ids.foldLeft(id.name){ case (a, d ~ e) => a + d + e.name} }

  def rebuild(a: Reader[Char], newSource: String, newOffset: Int): Reader[Char] = new Reader[Char] {
    def atEnd = a.atEnd
    def first = a.first
    def pos = a.pos
    def rest = rebuild(a.rest, newSource, offset + 1)
    override def source = newSource
    override def offset = newOffset
  }

  def cat(a: Reader[Char], b: Reader[Char]): Reader[Char] = {
    val aSource = a.source + b.source.subSequence(b.offset, b.source.length()).toString
    if(a.atEnd) {
      rebuild(b, aSource, a.offset)
    } else {
      new Reader[Char] {
        private lazy val result = cat(a.rest, b)
        def atEnd = a.atEnd
        def first = a.first
        def pos = a.pos
        def rest = result
        override def source = aSource
        override def offset = a.offset
      }
    }
  }

  lazy val oneLine: Parser[String] = regex(""".*(\r\n|\r|\n|$)""".r)

  lazy val hereDocument: Parser[StringNode] = ("""<<[a-zA-Z_][a-zA-Z0-9_]*""".r >> { t =>
    val tag = t.substring(2)
    Parser{in =>
      val Success(temp, rest) = oneLine(in)

      val line = new CharSequenceReader(temp, 0)
      hereDocumentBody(tag).apply(rest) match {
        case Success(value, next) =>
          val source = cat(line, next)
          Success(StringNode(value), source)
        case Failure(msg, next) => Failure(msg, cat(line, next))
        case Error(msg, next) => Error(msg, cat(line, next))
      }
    }
  }) <~ SPACING_WITHOUT_LF

  def hereDocumentBody(beginTag: String): Parser[String] = oneLine >> {line =>
    if(beginTag == line.trim) "" else hereDocumentBody(beginTag) ^^ {result =>
      line + result
    }
  }

  lazy val hereExpression: Parser[AstNode] = ("""<<\$[a-zA-Z_][a-zA-Z0-9_]*""".r >> { t =>
    val tag = t.substring(3)
    Parser{in =>
      val Success(temp, rest) = oneLine(in)

      val line = new CharSequenceReader(temp, 0)
      hereDocumentBody(tag).apply(rest) match {
        case Success(value, next) =>
          val Success(ast, _) = lines(new CharSequenceReader(value, 0))
          val source = cat(line, next)
          Success(ast, source)
        case Failure(msg, next) => Failure(msg, cat(line, next))
        case Error(msg, next) => Error(msg, cat(line, next))
      }
    }
  }) <~ SPACING_WITHOUT_LF

  def ident :Parser[Identifier] = ("""[A-Za-z_][a-zA-Z0-9]*""".r^? {
    case n if n != "if" && n!= "val" && n!= "println" && n != "def" => n
  } ^^ Identifier) <~ SPACING_WITHOUT_LF

  def assignment: Parser[Assignment] = (ident <~ CL(EQ)) ~ expression ^^ {
    case v ~ value => Assignment(v.name, value)
  }

  // val_declaration ::= "val" ident "=" expression
  def val_declaration:Parser[ValDeclaration] = (CL(VAL) ~> ident <~ CL(EQ)) ~ expression ^^ {
    case v ~ value => ValDeclaration(v.name, value)
  }
  // printLine ::= "printLn" "(" expression ")"
  def printLine: Parser[AstNode] = CL(PRINTLN) ~> (CL(LPAREN) ~> expression <~ RPAREN) ^^PrintLine

  // anonFun ::= "(" [param {"," param}] ")" "=>" expression
  def anonFun:Parser[AstNode] = (opt(CL(LPAREN) ~> repsep(ident, CL(COMMA)) <~ CL(RPAREN)) <~ CL(ARROW)) ~ expression ^^ {
    case Some(params) ~ proc => FunctionLiteral(params.map{_.name}, proc)
    case None ~ proc => FunctionLiteral(List(), proc)
  }

  // newObject ::= "new" fqcn "(" [param {"," param} ")"
  def newObject: Parser[AstNode] = CL(NEW) ~> fqcn ~ (opt(CL(LPAREN) ~> repsep(ident, CL(COMMA)) <~ (RPAREN))) ^^ {
    case className ~ Some(params) => NewObject(className, params)
    case className ~ None => NewObject(className, List())
  }

  // functionDefinition ::= "def" ident  ["(" [param {"," param]] ")"] "=" expression
  def functionDefinition:Parser[FunctionDefinition] = CL(DEF) ~> ident ~ opt(CL(LPAREN) ~>repsep(ident, CL(COMMA)) <~ CL(RPAREN)) ~ CL(EQ) ~ expression ^^ {
    case v~params~_~proc => {
        val p = params match {
          case Some(pr) => pr
          case None => Nil
        }
        FunctionDefinition(v.name, FunctionLiteral(p.map{_.name}, proc))
    }
  }

  def parse(str:String) = parseAll(lines, str)
}
