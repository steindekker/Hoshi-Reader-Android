//! A JSON tree in a bump arena: strings borrow from the bank buffer when they have
//! no escapes, everything else lives in the arena, which is reset after each bank
//! entry. Same semantics the Python code relies on: key order is kept, and a
//! repeated key keeps its first position and its last value, as a dict does.

use std::fmt;

use bumpalo::Bump;
use bumpalo::collections::Vec as BVec;
use serde::de::{self, DeserializeSeed, Deserializer, MapAccess, SeqAccess, Visitor};

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum J<'a> {
    Null,
    Bool(bool),
    Int(i64),
    UInt(u64),
    Float(f64),
    Str(&'a str),
    Arr(&'a [J<'a>]),
    Obj(&'a [(&'a str, J<'a>)]),
}

pub static NULL: J<'static> = J::Null;

impl<'a> J<'a> {
    /// `dict.get(key)`.
    pub fn get(&self, key: &str) -> Option<&J<'a>> {
        match self {
            J::Obj(m) => m.iter().find(|(k, _)| *k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&'a str> {
        match *self {
            J::Str(s) => Some(s),
            _ => None,
        }
    }

    /// An int as Python's `isinstance(v, int)` sees it (bools aside).
    pub fn as_i64(&self) -> Option<i64> {
        match *self {
            J::Int(i) => Some(i),
            J::UInt(u) => i64::try_from(u).ok(),
            _ => None,
        }
    }

    pub fn as_obj(&self) -> Option<&'a [(&'a str, J<'a>)]> {
        match *self {
            J::Obj(m) => Some(m),
            _ => None,
        }
    }
}

#[cfg(test)]
pub fn parse<'a>(s: &'a str, bump: &'a Bump) -> J<'a> {
    let mut de = serde_json::Deserializer::from_str(s);
    JSeed(bump).deserialize(&mut de).unwrap()
}

/// Deserializes one value into `J<'b>`.
#[derive(Clone, Copy)]
pub struct JSeed<'b>(pub &'b Bump);

/// Deserializes a string, borrowing from the input when it has no escapes.
#[derive(Clone, Copy)]
pub struct StrSeed<'b>(pub &'b Bump);

impl<'de: 'b, 'b> DeserializeSeed<'de> for StrSeed<'b> {
    type Value = &'b str;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<&'b str, D::Error> {
        d.deserialize_str(self)
    }
}

impl<'de: 'b, 'b> Visitor<'de> for StrSeed<'b> {
    type Value = &'b str;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a string")
    }
    fn visit_borrowed_str<E: de::Error>(self, v: &'de str) -> Result<&'b str, E> {
        Ok(v)
    }
    fn visit_str<E: de::Error>(self, v: &str) -> Result<&'b str, E> {
        Ok(self.0.alloc_str(v))
    }
}

/// `Option<str>` (null -> None).
#[derive(Clone, Copy)]
pub struct OptStrSeed<'b>(pub &'b Bump);

impl<'de: 'b, 'b> DeserializeSeed<'de> for OptStrSeed<'b> {
    type Value = Option<&'b str>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Option<&'b str>, D::Error> {
        d.deserialize_option(self)
    }
}

impl<'de: 'b, 'b> Visitor<'de> for OptStrSeed<'b> {
    type Value = Option<&'b str>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a string or null")
    }
    fn visit_none<E>(self) -> Result<Self::Value, E> {
        Ok(None)
    }
    fn visit_unit<E>(self) -> Result<Self::Value, E> {
        Ok(None)
    }
    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        StrSeed(self.0).deserialize(d).map(Some)
    }
}

impl<'de: 'b, 'b> DeserializeSeed<'de> for JSeed<'b> {
    type Value = J<'b>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<J<'b>, D::Error> {
        d.deserialize_any(self)
    }
}

impl<'de: 'b, 'b> Visitor<'de> for JSeed<'b> {
    type Value = J<'b>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("JSON")
    }
    fn visit_unit<E>(self) -> Result<J<'b>, E> {
        Ok(J::Null)
    }
    fn visit_none<E>(self) -> Result<J<'b>, E> {
        Ok(J::Null)
    }
    fn visit_bool<E>(self, v: bool) -> Result<J<'b>, E> {
        Ok(J::Bool(v))
    }
    fn visit_i64<E>(self, v: i64) -> Result<J<'b>, E> {
        Ok(J::Int(v))
    }
    fn visit_u64<E>(self, v: u64) -> Result<J<'b>, E> {
        Ok(J::UInt(v))
    }
    fn visit_f64<E>(self, v: f64) -> Result<J<'b>, E> {
        Ok(J::Float(v))
    }
    fn visit_borrowed_str<E>(self, v: &'de str) -> Result<J<'b>, E> {
        Ok(J::Str(v))
    }
    fn visit_str<E>(self, v: &str) -> Result<J<'b>, E> {
        Ok(J::Str(self.0.alloc_str(v)))
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<J<'b>, A::Error> {
        let mut v = BVec::new_in(self.0);
        while let Some(x) = seq.next_element_seed(self)? {
            v.push(x);
        }
        Ok(J::Arr(v.into_bump_slice()))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<J<'b>, A::Error> {
        let mut v: BVec<(&'b str, J<'b>)> = BVec::new_in(self.0);
        while let Some(k) = map.next_key_seed(StrSeed(self.0))? {
            let x = map.next_value_seed(self)?;
            match v.iter_mut().find(|(e, _)| *e == k) {
                Some(slot) => slot.1 = x,
                None => v.push((k, x)),
            }
        }
        Ok(J::Obj(v.into_bump_slice()))
    }
}
