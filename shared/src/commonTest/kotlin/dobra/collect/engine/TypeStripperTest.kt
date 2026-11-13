package dobra.collect.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TypeStripperTest {
    /** Compares token by token, so the blanks left where types were do not matter. */
    private fun assertStrips(typescript: String, javascript: String) {
        val stripped = TypeStripper.strip(typescript)
        assertEquals(tokens(javascript), tokens(stripped), "stripped:\n$stripped")
        assertEquals(typescript.length, stripped.length, "positions are kept")
        assertEquals(typescript.count { it == '\n' }, stripped.count { it == '\n' }, "lines are kept")
    }

    private fun tokens(source: String) = Lexer(source).lexAll().map { it.text }

    @Test
    fun variableAnnotations() {
        assertStrips("let x: number = 1, y: string[] = [];", "let x = 1, y = [];")
        assertStrips("const { a, b }: { a: string; b?: number } = o;", "const { a, b } = o;")
        assertStrips("const [p, q]: [string, number] = pair;", "const [p, q] = pair;")
        assertStrips("let x!: number;", "let x;")
        assertStrips("let a: Array<Array<number>>= [];", "let a = [];")
        assertStrips("let u: string | null = null\nlet v: (() => void) | undefined", "let u = null\nlet v")
    }

    @Test
    fun functionsAndArrows() {
        assertStrips(
            "function f<T extends object = {}>(a: T, b?: Array<Map<string, T>>, ...rest: number[]): Promise<void> " +
                "{ return g<T>(a as T); }",
            "function f(a, b, ...rest) { return g(a); }",
        )
        assertStrips(
            "const h = async (x: number, y = 2): Promise<number> => x ? y : 0;",
            "const h = async (x, y = 2) => x ? y : 0;",
        )
        assertStrips("const k = <T,>(v: T): T => v;", "const k = (v) => v;")
        assertStrips(
            "const fn = (cb: (err: Error | null, data?: string) => void): void => cb(null);",
            "const fn = (cb) => cb(null);",
        )
        assertStrips("function isItem(x: unknown): x is Item { return true; }", "function isItem(x) { return true; }")
        assertStrips("function g(this: Window, a: number) {}", "function g(a) {}")
        assertStrips(
            "function f(a: string): string;\nfunction f(a: any) { return a; }",
            "function f(a) { return a; }",
        )
        assertStrips("xs.map((s: string, i: number): string => s + i)", "xs.map((s, i) => s + i)")
        assertStrips("try {} catch (e: unknown) {}", "try {} catch (e) {}")
        assertStrips("function d(a = b ? c : e, f?: X) {}", "function d(a = b ? c : e, f) {}")
    }

    @Test
    fun declarationsAreRemovedWhole() {
        assertStrips(
            "interface Item { title: string; link?: string }\ntype Pair<A> = [A, A];\nconst n = 1;",
            "const n = 1;",
        )
        assertStrips(
            "type U<T> = T extends string ? \"s\" : T extends number ? \"n\" : never;\nlet q = 1;",
            "let q = 1;",
        )
        assertStrips("declare const API: string;\nconst a = 1;", "const a = 1;")
        assertStrips("import type { X } from \"./x\";\nconst a = 1;", "const a = 1;")
        assertStrips(
            "export default async function collect(input: Input): Promise<Output> {}",
            "async function collect(input) {}",
        )
        assertStrips("export interface A { a: number }\nexport const b = 1;", "const b = 1;")
    }

    @Test
    fun expressions() {
        assertStrips("const v = obj!.prop!.x as unknown as string;", "const v = obj.prop.x;")
        assertStrips("const a = b satisfies C;", "const a = b;")
        assertStrips("const x = y as const;", "const x = y;")
        assertStrips("const m = new Map<string, number>();", "const m = new Map();")
        assertStrips(
            "for (const [k, v] of Object.entries(m) as [string, number][]) {}",
            "for (const [k, v] of Object.entries(m)) {}",
        )
        assertStrips(
            "const o = { m<T>(x: T): T { return x; }, n: (y: number) => y, async p(z?: string) {} };",
            "const o = { m(x) { return x; }, n: (y) => y, async p(z) {} };",
        )
    }

    @Test
    fun plainJavaScriptIsUntouched() {
        val js = listOf(
            "const t = c ? a : b; const o = { a: 1, b: c ? 2 : 3 };",
            "if (x) !y && z();",
            "const s = a < b && c > d; for (let i = 0; i < n; i++) {}",
            "const gt = a >= b, sh = c >> 2, z = a >>> 1;",
            "const re = /a<b>c/g; const s = `\${a}<T>`;",
            "const z = c ? (a) : b;",
            "switch (x) { case 1: { break; } default: y = a ? b : c; }",
            "outer: for (;;) { break outer; }",
            "const f = function (a, b) { return a / b / 2; };",
            "const obj = { as: 1, type: 2, interface: 3 }; obj.as = obj.type;",
        )
        for (source in js) assertEquals(source, TypeStripper.strip(source))
    }

    @Test
    fun classes() {
        assertStrips(
            """
            abstract class Base<T> extends Root<T> implements A, B<T> {
              private readonly items: T[] = [];
              static count: number;
              declare hidden: string;
              name?: string;
              done!: boolean;
              [key: string]: unknown;
              constructor(n: string) { super(); this.name = n; }
              abstract run(): void;
              get size(): number { return this.items.length; }
              add<U>(item: T, extra?: U): this { this.items.push(item); return this; }
              over(a: string): void;
              over(a: any) {}
              handler = (e: Event): void => { this.count = e ? 1 : 2; }
              label = ok ? "yes" : "no"
              public last: string
            }
            """.trimIndent(),
            """
            class Base extends Root {
              items = [];
              static count;
              name;
              done;
              constructor(n) { super(); this.name = n; }
              get size() { return this.items.length; }
              add(item, extra) { this.items.push(item); return this; }
              over(a) {}
              handler = (e) => { this.count = e ? 1 : 2; }
              label = ok ? "yes" : "no"
              last
            }
            """.trimIndent(),
        )
    }

    @Test
    fun syntaxThatIsNotErasableIsRefusedWithItsPosition() {
        val refused = listOf(
            "enum Color { Red }",
            "const enum Color { Red }",
            "class A { constructor(private x: number) {} }",
            "import x from \"y\";",
            "namespace N {}",
            "@dec class A {}",
            "const v = <string>x;",
            "export { a };",
        )
        for (source in refused) {
            assertFailsWith<TypeScriptSyntaxException>(source) { TypeStripper.strip(source) }
        }
        val e = assertFailsWith<TypeScriptSyntaxException> { TypeStripper.strip("const a = 1;\n  enum E { A }") }
        assertEquals(2, e.line)
        assertEquals(3, e.column)
        assertTrue("enum" in e.message!!)
    }
}
