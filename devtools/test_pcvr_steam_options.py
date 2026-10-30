import importlib.util,pathlib,tempfile,unittest
spec=importlib.util.spec_from_file_location('options',pathlib.Path(__file__).with_name('pcvr-steam-options.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class OptionsTests(unittest.TestCase):
    config='"UserLocalConfigStore" { "Software" { "Valve" { "Steam" { "apps" { "438100" { "Playtime" "20" } "7" {"LaunchOptions" "keep"} } } } } }'
    def test_scoped_edit_and_quoting(self):
        out=m.edit(self.config,438100,'env A="a b" %command%')
        self.assertIn('"LaunchOptions" "keep"',out)
        self.assertIn('A=\\"a b\\"',out)
        self.assertEqual(out,m.edit(out,438100,'env A="a b" %command%'))
    def test_custom_options_are_not_destroyed(self):
        with self.assertRaises(ValueError):m.edit(self.config.replace('"Playtime" "20"','"LaunchOptions" "--no-vr"'),438100,'new')
    def test_exact_generated_upgrade_preserves_custom_options(self):
        old='env XR_RUNTIME_JSON="/old/runtime.json" %command%'
        new='env XR_RUNTIME_JSON="/new/runtime.json" %command%'
        original=m.edit(self.config,438100,old)
        upgraded=m.edit(original,438100,new,old)
        self.assertEqual(upgraded,m.edit(self.config,438100,new))
        with self.assertRaises(ValueError):m.edit(original,438100,new,old+' --different')
        customized=m.edit(self.config,438100,old+' --custom')
        with self.assertRaises(ValueError):m.edit(customized,438100,new,old)
    def test_verified_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);p=root/'user/config/localconfig.vdf';p.parent.mkdir(parents=True);p.write_text(self.config)
            backups=root/'backups'
            self.assertTrue(m.apply(p,438100,'env %command%',backups))
            self.assertEqual(next(backups.iterdir()).read_text(),self.config)
            self.assertFalse(m.apply(p,438100,'env %command%',backups))
if __name__=='__main__':unittest.main()
