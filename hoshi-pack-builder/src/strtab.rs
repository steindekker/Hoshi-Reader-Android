//! Interned strings in one arena: an id per distinct string, no per-string
//! allocation (the setup server has ~1 GB to spare).

use std::hash::BuildHasher;

use hashbrown::HashTable;

#[derive(Default)]
pub struct StrTable {
    buf: String,
    spans: Vec<(u32, u32)>,
    table: HashTable<u32>,
    hasher: foldhash::fast::FixedState,
}

impl StrTable {
    pub fn len(&self) -> usize {
        self.spans.len()
    }

    pub fn str(&self, id: u32) -> &str {
        let (o, l) = self.spans[id as usize];
        &self.buf[o as usize..(o + l) as usize]
    }

    pub fn get(&self, s: &str) -> Option<u32> {
        let h = self.hasher.hash_one(s);
        self.table.find(h, |&id| self.str(id) == s).copied()
    }

    pub fn intern(&mut self, s: &str) -> u32 {
        let h = self.hasher.hash_one(s);
        let (buf, spans) = (&self.buf, &self.spans);
        let at = |id: u32| {
            let (o, l) = spans[id as usize];
            &buf[o as usize..(o + l) as usize]
        };
        if let Some(&id) = self.table.find(h, |&id| at(id) == s) {
            return id;
        }
        let id = self.spans.len() as u32;
        let off = u32::try_from(self.buf.len()).expect("string table over 4 GB");
        self.buf.push_str(s);
        self.spans.push((off, s.len() as u32));
        let (buf, spans, hasher) = (&self.buf, &self.spans, &self.hasher);
        self.table.insert_unique(h, id, |&id| {
            let (o, l) = spans[id as usize];
            hasher.hash_one(&buf[o as usize..(o + l) as usize])
        });
        id
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn interns() {
        let mut t = super::StrTable::default();
        let a = t.intern("猫");
        let b = t.intern("");
        assert_eq!(t.intern("猫"), a);
        assert_eq!(t.get(""), Some(b));
        assert_eq!(t.get("犬"), None);
        assert_eq!(t.str(a), "猫");
    }
}
