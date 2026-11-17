import { type Container, dag, type Directory } from "@dagger.io/dagger";

const dependencies = [
  ["gardening-coursier", "/root/.cache/coursier"],
  ["gardening-sbt-boot", "/root/.sbt/boot"],
] as const;

const compiler = {
  coverage: [
    ["gardening-coverage-target", "/work/target"],
    ["gardening-coverage-project", "/work/project/target"],
    ["gardening-coverage-meta-project", "/work/project/project/target"],
  ],
  contract: [
    ["gardening-contract-target", "/work/target"],
    ["gardening-contract-project", "/work/project/target"],
    ["gardening-contract-meta-project", "/work/project/project/target"],
  ],
} as const;
const actions = ["gardening-sbt-actions", "/root/.cache/sbt/v2"] as const;
const volumes = [
  ...dependencies,
  ["gardening-npm", "/root/.npm"],
  actions,
  ...compiler.coverage,
  ...compiler.contract,
] as const;

export function backendCache(container: Container, mode?: keyof typeof compiler): Container {
  return [...dependencies, ...(mode ? [actions, ...compiler[mode]] : [])].reduce(
    (current, [name, path]) => current.withMountedCache(path, dag.cacheVolume(name)),
    container,
  );
}

function transfer(): Container {
  return volumes.reduce(
    (container, [name]) => container.withMountedCache(`/volumes/${name}`, dag.cacheVolume(name)),
    dag.container().from("alpine:3.23.3").withEnvVariable("CACHE_TRANSFER", crypto.randomUUID()),
  );
}

export function restoreCache(source: Directory): Promise<string> {
  return transfer()
    .withMountedDirectory("/snapshot", source)
    .withExec(["sh", "-ec", "cp -a /snapshot/. /volumes/"])
    .stdout();
}

export function cacheSnapshot(): Directory {
  return transfer()
    .withExec(["sh", "-ec", "mkdir /snapshot; cp -a /volumes/. /snapshot/; du -sk /snapshot/*"])
    .directory("/snapshot");
}
