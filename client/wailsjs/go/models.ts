export namespace main {
	
	export class FileFilter {
	    displayName: string;
	    pattern: string;
	
	    static createFrom(source: any = {}) {
	        return new FileFilter(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.displayName = source["displayName"];
	        this.pattern = source["pattern"];
	    }
	}
	export class VersionInfo {
	    version: string;
	    platform: string;
	    deviceName: string;
	    serverUrl: string;
	
	    static createFrom(source: any = {}) {
	        return new VersionInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.platform = source["platform"];
	        this.deviceName = source["deviceName"];
	        this.serverUrl = source["serverUrl"];
	    }
	}

}

