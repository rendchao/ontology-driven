<template>
  <div id="app">
    <router-view />
  </div>
</template>

<script>
export default {
  name: 'App'
}
</script>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
}
body {
  font-family: "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  font-size: 14px;
  color: #171717;
  background: #f5f6f8;
}
</style>
