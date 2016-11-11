'use strict';

const React = require("react");
const ReactDOM = require("react-dom");
const Buttons = require("../components/buttons");
const Network = require("../utils/network");
const AsyncButton = Buttons.AsyncButton;

function object2map(obj) {
  return Object.keys(obj).reduce((acc, id) => {
    acc.set(id, obj[id]);
    return acc;
  }, new Map());
}

class MinionResultView extends React.Component {

  constructor(props) {
    super(props);
    this.state = {
      open: false,
    };
    ["onClick"]
    .forEach(method => this[method] = this[method].bind(this));
  }

  onClick() {
    if (this.props.result != "pending" && this.props.result != "timedOut" && this.props.result != "matched") {
      this.setState({open: !this.state.open})
    }
  }

  render() {
    const id = this.props.id;
    const result = this.props.result;
    const props = this.props;
    var isRunResult = false;
    return (
      <div className="panel panel-default">
        <div id={id} className="panel-heading" onClick={this.onClick} style={props.result ? {cursor: "pointer"} : {cursor: "default"}}>
           <span>{id}</span>
           {(() => {
              if(result == "pending") {
                  return (
                    <div className="badge pull-right">
                       {t("pending")}
                    </div>
                  );
              } else if(result == "timedOut") {
                  return (
                    <div className="badge pull-right">
                       {t("timed out")}
                    </div>
                  );
              } else if(result == "matched") {
                  // nothing
              } else {
                  isRunResult = true;
                  return(
                          <div className="pull-right">
                            <div className="badge">
                              {this.state.open ? t("- hide response -") : t("- show response -")}
                            </div>
                            <i className="fa fa-right fa-check-circle fa-1-5x"></i>
                          </div>
                     );
              }
           })()}
        </div>
        {
          this.state.open && isRunResult != null ?
            <div className="panel-body">
               <pre id={id + '-results'}>{result}</pre>
            </div>
            : undefined
        }
      </div>
    )
  }
}

function isPreviewDone(previewed, minionsMap) {
  const r = !previewed && Array.from(minionsMap, (e) => e[1]).every((v) => v == "matched" || v == "timedOut");
  console.log("p=" + previewed + " m=" + Array.from(minionsMap, (e) => e[1]) + " r=" + r);
  return r;
}

function isTimedOutDone(minionsMap) {
 const results = Array.from(minionsMap, (e) => e[1]);
 return results.every((v) => v != "pending") && results.some((v) => v == "timedOut");
}


class RemoteCommand extends React.Component {

  constructor() {
    super();
    ["onPreview", "onRun", "commandChanged", "targetChanged", "commandResult", "previewAsync", "runAsync"]
    .forEach(method => this[method] = this[method].bind(this));
    this.state = {
      command: "ls -lha",
      target: "*",
      result: {
        minions: new Map()
      },
      previewed: false,
      errors: []
    };
  }

  render() {
    var errs = null;
    const style = {
        paddingBottom: "0px"
    }
    if (this.state.errors) {
        this.state.errors.map( msg => {
            errs = <div className="alert alert-danger">{msg}</div>
        })
    }

    //<AsyncButton id="preview" name={t("Preview")} action={this.onPreview} />
    //<AsyncButton id="run" disabled={this.state.previewed ? "" : "disabled"} name={t("Run")} action={this.onRun} />
    return (
      <div>
          {errs}
          <div id="remote-root" className="spacewalk-toolbar-h1">
            <h1>
              <i className="fa fa-desktop"></i>
              {t("Remote Commands")}
            </h1>
          </div>
          <div className="panel panel-default">
            <div className="panel-body">
              <div className="row">
                <div className="col-lg-12">
                  <div className="input-group">
                      <input id="command" className="form-control" type="text" defaultValue={this.state.command} onChange={this.commandChanged} />
                      <span className="input-group-addon">@</span>
                      <input id="target" className="form-control" type="text" defaultValue={this.state.target} onChange={this.targetChanged} />
                      <div className="input-group-btn">
                        <button id="wspreview" className="btn btn-default" onClick={this.previewAsync}>Preview</button>
                        <button id="wsrun" className="btn btn-default" disabled={this.state.previewed ? "" : "disabled"} onClick={this.runAsync}>Run</button>
                      </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div className="panel panel-default">
            <div className="panel-heading">
              <h4>
                <span>{t("Target systems")}</span>
                <span>{this.state.result.minions.size ? " (" + this.state.result.minions.size + ")" : undefined}</span>
              </h4>
            </div>
            <div className="panel-body" style={this.state.result.minions.size ? style : undefined}>
              {(() => {
                if (!this.state.result.minions.size) {
                   return(<span>{!this.state.previewed ? t("No target systems previewed") : t("No target systems have been found")}</span>)
                } else {
                  return(this.commandResult(this.state.result))
                }
              })()}
            </div>
          </div>
      </div>
    );
  }

  previewAsync() {
    this.state.websocket.send(JSON.stringify({
                              preview: true,
                              target: this.state.target
                            }));
    this.setState({
        errors: [],
        previewed: false,
    });
  }

  runAsync() {
    this.state.websocket.send(JSON.stringify({
                              preview: false,
                              target: this.state.target,
                              command: this.state.command
                            }));
    this.setState({
        errors: []
    });
  }

  componentDidMount() {
    var ws = new WebSocket("wss://" + window.location.hostname + "/rhn/websocket/minion/remote-commands", "protocolOne");
    ws.onopen = () => {
      // Web Socket is connected, send data using send()
      console.log("Websocket opened");
    };
    ws.onerror = (e) => {
        this.setState({
            errors: [t("Error connecting to server.")]
            console.log(e);
        });
    };
    ws.onclose = (e) => {
        this.setState({
            errors: [t("Connection to server closed")]
            console.log(e);
        });
    };
    ws.onmessage = (e) => {
      console.log("Got websocket message: " + e.data);
      var event = JSON.parse(e.data);
      switch(event.type) {
        case "asyncJobStart":
            this.setState({
                result: {
                    minions: event.minions.reduce((map, minionId) => map.set(minionId, "pending") , new Map())
                }
            });
        case "match":
            var minionsMap = this.state.result.minions;
            minionsMap.set(event.minion, "matched");
            const previewDone = isPreviewDone(this.state.previewed, minionsMap);
            this.setState({
                previewed: previewDone,
                result: {
                    minions: minionsMap
                }
            });
        case "runResult":
            var minionsMap = this.state.result.minions;
            minionsMap.set(event.minion, event.out);
            this.setState({
                result: {
                    minions: minionsMap
                }
            });
        case "timedOut":
            var minionsMap = this.state.result.minions;
            minionsMap.set(event.minion, "timedOut");
            const timedOutDone = isTimedOutDone(minionsMap);
            const previewDone = isPreviewDone(this.state.previewed, minionsMap);
            this.setState({
                errors: timedOutDone ? [t("Not all minions responded.")] : [],
                previewed: previewDone,
                result: {
                    minions: minionsMap
                }
            });
      }
    };
    this.setState({
        websocket: ws
    });
  }

  targetChanged(event) {
    this.setState({
      target: event.target.value,
      previewed: false
    });
  }

  commandChanged(event) {
    this.setState({
      command: event.target.value
    });
  }

  commandResult(result) {
    const elements = [];
    for(var kv of result.minions) {
      const id = kv[0];
      const value = kv[1];
      elements.push(
        <MinionResultView key={id} id={id} result={value}/>
      );
    }
    return <div>{elements}</div>;
  }
}

function errorMessageByStatus(status) {
  if (status == 401) {
    return [t("Session expired, please reload the page to run command on systems.")];
  }
  else if (status == 403) {
    return [t("Authorization error, please reload the page or try to logout/login again.")];
  }
  else if (status >= 500) {
    return [t("Server error, please check log files.")];
  }
  else {
    return [];
  }
}

ReactDOM.render(
  <RemoteCommand />,
  document.getElementById('remote-commands')
);
