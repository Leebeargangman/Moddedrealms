// Tiny logger: every console line gets a time and who it is about, e.g.
//   18:02:11 [Alder] decided gather oak_log x16 "Getting wood for the hall"

const time = () => new Date().toTimeString().slice(0, 8);

export const log = {
  info: (who, ...msg) => console.log(`${time()} [${who}]`, ...msg),
  warn: (who, ...msg) => console.warn(`${time()} [${who}] WARNING:`, ...msg),
  error: (who, ...msg) => console.error(`${time()} [${who}] ERROR:`, ...msg),
};
