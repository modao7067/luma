import pathlib
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli import balances

class BalancesTest(unittest.TestCase):
    def test_signed_in_account_is_the_only_balance(self):
        official = {'status': 'ready', 'wallets': [{'currency': 'CNY', 'total': '9.2'}]}
        account = {'status': 'ready', 'wallets': [{'currency': 'CNY', 'total': '1.5'}],
                   'bonus': [{'currency': 'CNY', 'total': '3'}]}
        self.assertEqual(balances.rows(official, account), [
            {'label': '账户余额 · CNY', 'value': '¥1.50'},
        ])

    def test_api_key_is_used_only_when_the_account_is_unavailable(self):
        official = {'status': 'ready', 'wallets': [{'currency': 'CNY', 'total': '9.2'}]}
        self.assertEqual(balances.rows(official, {'status': 'unavailable'}), [
            {'label': 'API Key 余额 · CNY', 'value': '¥9.20'},
        ])

    def test_unavailable_is_never_rendered_as_zero(self):
        rows = balances.rows({'status': 'unavailable'}, {'status': 'unavailable'})
        self.assertEqual(rows, [{'label': '余额', 'value': '不可用'}])

    def test_account_grant_block_is_read_without_yaml_dependency(self):
        text = ('records:\n'
                '  other: {}\n'
                '  deepseek-account-platform/default:\n'
                '    {\n'
                '      kind: grant,\n'
                '      payload:\n'
                '      {\n'
                '        token: abc,\n'
                '        issuer: https://platform.deepseek.com\n'
                '      }\n'
                '    }\n')
        self.assertEqual(balances._account_token(text),
                         ('abc', 'https://platform.deepseek.com'))

    def test_api_key_flow_mapping_is_read(self):
        self.assertEqual(balances._api_key('refs: { DEEPSEEK_API_KEY: sk-test }\n'), 'sk-test')

if __name__ == '__main__':
    unittest.main(verbosity=2)
