#!/usr/bin/env python3
"""Regression tests for check-api-parity.py on synthetic checkouts."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('apiparity', Path(__file__).with_name('check-api-parity.py'))
apiparity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apiparity)

API = '''public final class io/github/kaeferfreund/socketio/Socket {
\tpublic final fun emit (Ljava/lang/String;[Ljava/lang/Object;)Lio/github/kaeferfreund/socketio/Socket;
\tpublic final fun getTimeout-FghU774 ()Lkotlin/time/Duration;
\tpublic static final field PROTOCOL I
}

public abstract interface class io/github/kaeferfreund/socketio/Subscription : io/github/kaeferfreund/socketio/engineio/Cancellable {
}
'''

TS = '''export class Socket<
  E extends EventsMap = DefaultEventsMap,
> extends Emitter<E> {
  public readonly io: Manager;
  /**
   * docs(): not a member
   */
  public emit(ev: string): this {
  }
  private _secret(): void {}
  protected onpacket(packet: Packet) {}
  get timeout() {
    return 1;
  }
}
'''


def member(status, **fields):
    return dict(status=status, **fields)


class ApiParityTest(unittest.TestCase):
    def checkout(self, members):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        (root / 'Documentation').mkdir()
        (root / 'client/api').mkdir(parents=True)
        (root / 'client/api/client.api').write_text(API)
        mapping = {'upstream_sha': 'x', 'surfaces': [{'name': 'Socket', 'source': 'socket.ts', 'kind': 'class', 'declaration': 'Socket', 'members': members}]}
        (root / 'Documentation/JavaScriptApiMapping.json').write_text(json.dumps(mapping))
        return root

    def test_existing_symbols_and_reasons_pass(self):
        root = self.checkout({
            'emit': member('implemented', kotlin=['io/github/kaeferfreund/socketio/Socket#emit']),
            'timeout': member('adapted', kotlin=['io/github/kaeferfreund/socketio/Socket#getTimeout'], note='a Duration'),
            'io': member('not-implemented', reason='internal'),
        })
        self.assertEqual([], apiparity.validate(root=root))

    def test_a_missing_kotlin_member_fails(self):
        root = self.checkout({'emit': member('implemented', kotlin=['io/github/kaeferfreund/socketio/Socket#emitLater'])})
        self.assertIn('Socket.emit: no member io/github/kaeferfreund/socketio/Socket#emitLater in api/*.api', apiparity.validate(root=root))

    def test_interface_classes_and_fields_are_read(self):
        api = apiparity.kotlin_api(self.checkout({}))
        self.assertIn('io/github/kaeferfreund/socketio/Subscription', api)
        self.assertIn('PROTOCOL', api['io/github/kaeferfreund/socketio/Socket'])

    def test_adapted_needs_a_note_and_others_a_reason(self):
        root = self.checkout({
            'emit': member('adapted', kotlin=['io/github/kaeferfreund/socketio/Socket#emit']),
            'io': member('not-applicable'),
        })
        errors = apiparity.validate(root=root)
        self.assertIn('Socket.emit: an adapted member needs a note', errors)
        self.assertIn('Socket.io: needs a reviewed reason', errors)

    def test_typescript_members_skip_private_protected_and_comments(self):
        self.assertEqual({'io', 'emit', 'timeout'}, apiparity.typescript_members(TS, 'class', 'Socket'))


if __name__ == '__main__':
    unittest.main()
