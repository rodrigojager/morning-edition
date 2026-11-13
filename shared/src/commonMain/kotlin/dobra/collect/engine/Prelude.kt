package dobra.collect.engine

/**
 * JavaScript evaluated before the collector, in the collector's own fresh runtime.
 *
 * - Takes the three host functions the engine defined (`__dobraFetch`, `__dobraDone`, `__dobraFail`) into a closure
 *   and deletes them from the global object: the script sees only `fetch`.
 * - `fetch(address)` resolves to a small, frozen response: `ok`, `status`, `url`, `headers.get("content-type")`,
 *   `text()`, `json()`. A refused or failed fetch rejects with a `TypeError` carrying the reason.
 * - Takes the clock and randomness away: `Date.now()`, `Date()` and `new Date()` without arguments and
 *   `Math.random()` throw. `new Date(value)`, `Date.parse` and `Date.UTC` still read dates found in pages.
 *   `WeakRef` and `FinalizationRegistry` (which expose garbage-collection timing) are removed.
 * - Defines `__dobraRun(input)`, which calls the collector's `collect(input)` and hands its result, as JSON, back.
 */
internal const val PRELUDE: String = """
(function (g) {
  'use strict';
  const hostFetch = g.__dobraFetch, done = g.__dobraDone, fail = g.__dobraFail;
  delete g.__dobraFetch; delete g.__dobraDone; delete g.__dobraFail;
  const describe = (e) => {
    if (e instanceof Error) {
      const where = e.stack ? '\n' + String(e.stack).trim() : '';
      return (e.name || 'Error') + ': ' + e.message + where;
    }
    try { return 'thrown: ' + String(e); } catch (_) { return 'thrown: (a value that cannot be printed)'; }
  };
  const response = (r) => Object.freeze({
    ok: r.status >= 200 && r.status < 300,
    status: r.status,
    url: r.url,
    headers: Object.freeze({ get: (name) => String(name).toLowerCase() === 'content-type' ? r.contentType : null }),
    text: async () => r.body,
    json: async () => JSON.parse(r.body),
  });
  g.fetch = async function fetch(address) {
    const r = JSON.parse(await hostFetch(String(address)));
    if (r.error) throw new TypeError(r.error);
    return response(r);
  };
  const RealDate = Date;
  const noClock = () => {
    throw new Error('collectors cannot read the clock: Date.now(), Date() and new Date() without arguments are not available');
  };
  function SafeDate(...args) {
    if (!new.target || args.length === 0) noClock();
    return Reflect.construct(RealDate, args, new.target);
  }
  SafeDate.prototype = RealDate.prototype;
  Object.defineProperty(RealDate.prototype, 'constructor', { value: SafeDate, writable: true, configurable: true });
  SafeDate.UTC = RealDate.UTC;
  SafeDate.parse = RealDate.parse;
  SafeDate.now = noClock;
  g.Date = SafeDate;
  Math.random = () => { throw new Error('collectors cannot use random numbers'); };
  delete g.WeakRef;
  delete g.FinalizationRegistry;
  g.__dobraRun = function (input) {
    let pending;
    try {
      if (typeof collect !== 'function') throw new Error('the script must define a function named collect(input)');
      pending = Promise.resolve(collect(input));
    } catch (e) { fail(describe(e)); return; }
    pending.then((result) => {
      let json;
      try { json = JSON.stringify(result === undefined ? null : result); }
      catch (e) { fail('the result is not plain data: ' + describe(e)); return; }
      done(json === undefined ? 'null' : json);
    }, (e) => fail(describe(e)));
  };
})(globalThis);
"""
