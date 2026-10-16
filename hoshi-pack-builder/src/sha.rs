//! SHA-256 through ring, whose assembly is about twice as fast as the portable
//! code on CPUs without SHA extensions (the pack hashes ~1 GB per build).

pub struct Sha256(ring::digest::Context);

impl Sha256 {
    pub fn new() -> Self {
        Sha256(ring::digest::Context::new(&ring::digest::SHA256))
    }

    pub fn update(&mut self, data: impl AsRef<[u8]>) {
        self.0.update(data.as_ref());
    }

    pub fn finalize(self) -> Vec<u8> {
        self.0.finish().as_ref().to_vec()
    }
}
